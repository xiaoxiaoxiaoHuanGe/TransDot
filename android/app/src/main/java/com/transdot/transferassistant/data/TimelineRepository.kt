package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TimelineMessage(
    val id: String,
    val type: String,
    val batchId: String?,
    val sourceDeviceId: String,
    val sourceDeviceType: String,
    val textContent: String?,
    val createdAt: String,
    val metadataExpiresAt: String?,
)

data class MessagePage(
    val messages: List<TimelineMessage>,
    val nextBefore: String?,
)

data class MessageContext(
    val targetMessageId: String,
    val messages: List<TimelineMessage>,
)

sealed interface TimelineEvent {
    data class Created(val message: TimelineMessage) : TimelineEvent
    data class Deleted(val messageId: String) : TimelineEvent
    data object DeviceReplaced : TimelineEvent
    data object Unknown : TimelineEvent
}

sealed class TimelineFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized : TimelineFailure("Android Master 凭据已失效。")
    class Invalid(message: String) : TimelineFailure(message)
    class NotFound : TimelineFailure("消息已不存在。")
    class Network(cause: Throwable) : TimelineFailure("无法连接服务器，请检查网络。", cause)
    class Server(message: String, cause: Throwable? = null) : TimelineFailure(message, cause)
}

interface RealtimeConnection {
    fun close()
}

interface TimelineRealtimeListener {
    fun onOpen()
    fun onEvent(event: TimelineEvent)
    fun onClosed()
    fun onFailure(failure: TimelineFailure)
}

interface TimelineRepository {
    suspend fun list(session: StoredSession, before: String? = null): MessagePage
    suspend fun sendText(session: StoredSession, text: String): TimelineMessage
    suspend fun delete(session: StoredSession, messageId: String)
    suspend fun search(session: StoredSession, query: String): List<TimelineMessage>
    suspend fun context(session: StoredSession, messageId: String): MessageContext
    fun connect(session: StoredSession, listener: TimelineRealtimeListener): RealtimeConnection
}

class NetworkTimelineRepository(
    private val allowCleartext: Boolean,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) : TimelineRepository {
    override suspend fun list(session: StoredSession, before: String?): MessagePage = withContext(Dispatchers.IO) {
        val suffix = before?.takeIf(String::isNotBlank)?.let { "&before=${urlEncode(it)}" }.orEmpty()
        val response = execute(session, "/api/v1/messages?limit=50$suffix")
        val json = parseObject(response.body)
        MessagePage(
            messages = parseMessages(json.optJSONArray("messages") ?: JSONArray()),
            nextBefore = json.optString("next_before").takeIf(String::isNotBlank),
        )
    }

    override suspend fun sendText(session: StoredSession, text: String): TimelineMessage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).toString()
        parseMessage(parseObject(execute(session, "/api/v1/messages/text", "POST", body).body))
    }

    override suspend fun delete(session: StoredSession, messageId: String) = withContext(Dispatchers.IO) {
        execute(session, "/api/v1/messages/${urlEncode(messageId)}", "DELETE")
        Unit
    }

    override suspend fun search(session: StoredSession, query: String): List<TimelineMessage> = withContext(Dispatchers.IO) {
        val json = parseObject(execute(session, "/api/v1/search?q=${urlEncode(query)}").body)
        parseMessages(json.optJSONArray("results") ?: JSONArray())
    }

    override suspend fun context(session: StoredSession, messageId: String): MessageContext = withContext(Dispatchers.IO) {
        val json = parseObject(execute(session, "/api/v1/messages/${urlEncode(messageId)}/context").body)
        MessageContext(
            targetMessageId = json.getString("target_message_id"),
            messages = parseMessages(json.getJSONArray("messages")),
        )
    }

    override fun connect(session: StoredSession, listener: TimelineRealtimeListener): RealtimeConnection {
        val serverAddress = ServerAddress.normalize(session.serverAddress, allowCleartext)
        val websocketAddress = when {
            serverAddress.startsWith("https://") -> "wss://${serverAddress.removePrefix("https://")}/ws"
            else -> "ws://${serverAddress.removePrefix("http://")}/ws"
        }
        val request = Request.Builder()
            .url(websocketAddress)
            .header("Authorization", "Bearer ${session.masterToken}")
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { parseEvent(text) }.getOrDefault(TimelineEvent.Unknown)
                listener.onEvent(event)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                val failure = if (response?.code == 401) {
                    TimelineFailure.Unauthorized()
                } else {
                    TimelineFailure.Network(throwable)
                }
                listener.onFailure(failure)
            }
        })
        return object : RealtimeConnection {
            override fun close() {
                if (!socket.close(NORMAL_CLOSURE, "app background")) socket.cancel()
            }
        }
    }

    private fun execute(
        session: StoredSession,
        path: String,
        method: String = "GET",
        requestBody: String? = null,
    ): HttpResponse {
        val serverAddress = ServerAddress.normalize(session.serverAddress, allowCleartext)
        val builder = Request.Builder()
            .url("$serverAddress$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.masterToken}")
        when (method) {
            "POST" -> builder.post(requireNotNull(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            "DELETE" -> builder.delete()
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val body = readLimited(response)
                if (!response.isSuccessful) throw mapFailure(response.code, body)
                return HttpResponse(response.code, body)
            }
        } catch (failure: TimelineFailure) {
            throw failure
        } catch (failure: IOException) {
            throw TimelineFailure.Network(failure)
        } catch (failure: RuntimeException) {
            throw TimelineFailure.Server("服务器响应无法解析。", failure)
        }
    }

    private fun readLimited(response: Response): String {
        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BYTES) throw TimelineFailure.Server("服务器响应过大。")
        val bytes = body.bytes()
        if (bytes.size > MAX_RESPONSE_BYTES) throw TimelineFailure.Server("服务器响应过大。")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun mapFailure(status: Int, body: String): TimelineFailure {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        return when (error?.optString("code")) {
            "UNAUTHORIZED", "DEVICE_REVOKED" -> TimelineFailure.Unauthorized()
            "TEXT_EMPTY", "TEXT_TOO_LARGE", "TEXT_INVALID_UTF8", "SEARCH_INVALID" ->
                TimelineFailure.Invalid(error.optString("message").ifBlank { "请求内容无效。" })
            "MESSAGE_NOT_FOUND" -> TimelineFailure.NotFound()
            else -> TimelineFailure.Server(error?.optString("message").orEmpty().ifBlank {
                "服务器请求失败（HTTP $status）。"
            })
        }
    }

    private fun parseObject(value: String): JSONObject = try {
        JSONObject(value)
    } catch (failure: JSONException) {
        throw TimelineFailure.Server("服务器响应无法解析。", failure)
    }

    private fun parseMessages(array: JSONArray): List<TimelineMessage> = buildList {
        for (index in 0 until array.length()) add(parseMessage(array.getJSONObject(index)))
    }

    private fun parseMessage(json: JSONObject) = TimelineMessage(
        id = json.getString("id"),
        type = json.getString("type"),
        batchId = json.optNullableString("batch_id"),
        sourceDeviceId = json.getString("source_device_id"),
        sourceDeviceType = json.getString("source_device_type"),
        textContent = json.optNullableString("text_content"),
        createdAt = json.getString("created_at"),
        metadataExpiresAt = json.optNullableString("metadata_expires_at"),
    )

    private fun parseEvent(rawValue: String): TimelineEvent {
        val json = JSONObject(rawValue)
        return when (json.optString("type")) {
            "message.created" -> TimelineEvent.Created(parseMessage(json.getJSONObject("data")))
            "message.deleted" -> TimelineEvent.Deleted(json.getJSONObject("data").getString("message_id"))
            "device.replaced" -> TimelineEvent.DeviceReplaced
            else -> TimelineEvent.Unknown
        }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class HttpResponse(val status: Int, val body: String)

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val NORMAL_CLOSURE = 1000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
