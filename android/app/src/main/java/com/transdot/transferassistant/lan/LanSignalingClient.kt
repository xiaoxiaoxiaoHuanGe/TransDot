package com.transdot.transferassistant.lan

import com.transdot.transferassistant.data.StoredSession
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class LanSocketRequest(val url: String, val authorization: String)
data class LanIceCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int)

interface LanSocket { fun send(text: String): Boolean; fun close() }
interface LanSocketListener { fun onOpen(); fun onMessage(text: String); fun onClosed(); fun onFailure(error: Throwable) }
interface LanWebSocketTransport { fun connect(request: LanSocketRequest, listener: LanSocketListener): LanSocket }

sealed interface LanSignalEvent {
    data object Waiting : LanSignalEvent
    data class PeerOnline(val sessionId: String) : LanSignalEvent
    data class Offer(val sessionId: String, val sdp: String) : LanSignalEvent
    data class Ice(val sessionId: String, val ice: LanIceCandidate) : LanSignalEvent
    data class PeerOffline(val sessionId: String?) : LanSignalEvent
    data class Error(val code: String) : LanSignalEvent
}

class OkHttpLanWebSocketTransport(private val client: OkHttpClient) : LanWebSocketTransport {
    override fun connect(request: LanSocketRequest, listener: LanSocketListener): LanSocket {
        val socket = client.newWebSocket(
            Request.Builder().url(request.url).header("Authorization", request.authorization).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)
            },
        )
        return object : LanSocket {
            override fun send(text: String) = socket.send(text)
            override fun close() { socket.close(1000, "lan.leave") }
        }
    }
}

class LanSignalingClient(
    private val session: StoredSession,
    private val transport: LanWebSocketTransport,
    private val scope: CoroutineScope,
    private val reconnectDelayMillis: Long = 800,
) : LanSignalSource {
    private val mutableEvents = MutableSharedFlow<LanSignalEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<LanSignalEvent> = mutableEvents
    private var socket: LanSocket? = null
    private var sessionId: String? = null
    private var generation = 0L
    private var closed = false
    private var reconnectJob: Job? = null

    fun start() {
        if (closed || socket != null) return
        connect()
    }

    override fun sendAnswer(sdp: String): Boolean = sendSession("lan.answer", JSONObject().put("sdp", sdp))
    override fun sendIce(candidate: LanIceCandidate): Boolean {
        if (!isHostCandidate(candidate.candidate) || candidate.sdpMLineIndex < 0) return false
        val data = JSONObject()
            .put("candidate", candidate.candidate)
            .put("sdp_mid", candidate.sdpMid ?: JSONObject.NULL)
            .put("sdp_mline_index", candidate.sdpMLineIndex)
        return sendSession("lan.ice", data)
    }
    override fun markConnected(): Boolean = sendSession("lan.connected", JSONObject())
    override fun restartSession(): Boolean {
        if (closed || socket == null) return false
        if (sessionId != null && !sendSession("lan.leave", JSONObject())) return false
        sessionId = null
        return send("lan.ready", null, JSONObject())
    }

    fun close() {
        if (closed) return
        closed = true
        reconnectJob?.cancel()
        if (sessionId != null) sendSession("lan.leave", JSONObject())
        generation += 1
        socket?.close()
        socket = null
        sessionId = null
    }

    private fun connect() {
        val currentGeneration = ++generation
        val listener = object : LanSocketListener {
            override fun onOpen() {
                if (!isCurrent(currentGeneration)) return
                send("lan.ready", null, JSONObject())
            }
            override fun onMessage(text: String) {
                if (!isCurrent(currentGeneration)) return
                runCatching { parseEvent(text) }.onSuccess { event ->
                    if (event == null) return@onSuccess
                    if (event is LanSignalEvent.PeerOnline) sessionId = event.sessionId
                    if (event is LanSignalEvent.PeerOffline && event.sessionId == sessionId) sessionId = null
                    mutableEvents.tryEmit(event)
                }.onFailure { mutableEvents.tryEmit(LanSignalEvent.Error("LAN_SIGNAL_INVALID")) }
            }
            override fun onClosed() = disconnect(currentGeneration)
            override fun onFailure(error: Throwable) = disconnect(currentGeneration)
        }
        socket = transport.connect(LanSocketRequest(webSocketUrl(session.serverAddress), "Bearer ${session.masterToken}"), listener)
    }

    private fun disconnect(currentGeneration: Long) {
        if (!isCurrent(currentGeneration)) return
        generation += 1
        socket = null
        sessionId = null
        mutableEvents.tryEmit(LanSignalEvent.Waiting)
        if (!closed) reconnectJob = scope.launch { delay(reconnectDelayMillis); if (!closed && socket == null) connect() }
    }

    private fun sendSession(type: String, data: JSONObject): Boolean {
        val id = sessionId ?: return false
        return send(type, id, data)
    }

    private fun send(type: String, id: String?, data: JSONObject): Boolean {
        val value = JSONObject().put("type", type).put("timestamp", Instant.now().toString()).put("data", data)
        if (id != null) value.put("session_id", id)
        return socket?.send(value.toString()) == true
    }

    private fun isCurrent(value: Long) = !closed && value == generation
}

internal fun webSocketUrl(serverAddress: String): String = when {
    serverAddress.startsWith("https://") -> "wss://${serverAddress.removePrefix("https://").trimEnd('/')}/ws"
    serverAddress.startsWith("http://") -> "ws://${serverAddress.removePrefix("http://").trimEnd('/')}/ws"
    else -> throw IllegalArgumentException("LAN_SERVER_ADDRESS_INVALID")
}

internal fun isHostCandidate(candidate: String): Boolean = Regex("(?:^|\\s)typ\\s+host(?:\\s|$)").containsMatchIn(candidate)

private fun parseEvent(text: String): LanSignalEvent? {
    val value = JSONObject(text)
    val keys = value.keys().asSequence().toSet()
    if (keys != setOf("event_id", "type", "timestamp", "data")) throw IllegalArgumentException()
    value.getString("event_id")
    val type = value.getString("type")
    value.getString("timestamp")
    if (!type.startsWith("lan.")) return null
    val payload = value.getJSONObject("data")
    val data = payload.optJSONObject("data") ?: JSONObject()
    val id = payload.optString("session_id").takeIf(String::isNotBlank)
    return when (type) {
        "lan.peer_online" -> LanSignalEvent.PeerOnline(requireNotNull(id))
        "lan.offer" -> LanSignalEvent.Offer(requireNotNull(id), data.getString("sdp"))
        "lan.ice" -> {
            val sdpMid = if (data.isNull("sdp_mid")) null else data.getString("sdp_mid")
            val sdpMLineIndex = data.getInt("sdp_mline_index")
            if (sdpMLineIndex < 0 || (sdpMid != null && sdpMid.isBlank())) throw IllegalArgumentException()
            LanSignalEvent.Ice(requireNotNull(id), LanIceCandidate(data.getString("candidate"), sdpMid, sdpMLineIndex))
        }
        "lan.peer_offline", "lan.cancelled" -> LanSignalEvent.PeerOffline(id)
        "lan.error" -> LanSignalEvent.Error(payload.getString("code"))
        else -> throw IllegalArgumentException()
    }
}
