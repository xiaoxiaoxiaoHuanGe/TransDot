package com.transdot.transferassistant.lan

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

interface LanSignalSource {
    val events: Flow<LanSignalEvent>
    fun sendAnswer(sdp: String): Boolean
    fun sendIce(candidate: String): Boolean
    fun markConnected(): Boolean
}

sealed interface LanPeerState {
    data object Idle : LanPeerState
    data object Waiting : LanPeerState
    data object Connecting : LanPeerState
    data object Connected : LanPeerState
    data class Failed(val code: String) : LanPeerState
    data object Closed : LanPeerState
}

sealed interface LanChannelMessage {
    data class Control(val text: String) : LanChannelMessage
    data class Binary(val bytes: ByteArray) : LanChannelMessage
}

interface LanSdpCallback {
    fun onSuccess(sdp: String)
    fun onFailure(code: String)
}

interface LanPeerObserver {
    fun onIceCandidate(candidate: String)
    fun onDataChannel(channel: LanDataChannel)
    fun onFailed(code: String)
}

interface LanDataChannelObserver {
    fun onOpen()
    fun onText(text: String) {}
    fun onBinary(bytes: ByteArray) {}
    fun onBufferedAmountChange(previousAmount: Long) {}
    fun onClosed()
}

interface LanDataChannel {
    val isOpen: Boolean
    val isOrdered: Boolean
    val bufferedAmount: Long
    fun setObserver(observer: LanDataChannelObserver)
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    fun close()
}

const val LAN_BUFFER_HIGH_BYTES = 4L * 1024 * 1024
const val LAN_BUFFER_LOW_BYTES = 1L * 1024 * 1024

interface LanPeerConnection {
    fun setRemoteOffer(sdp: String, callback: LanSdpCallback)
    fun createAnswer(callback: LanSdpCallback)
    fun setLocalAnswer(sdp: String, callback: LanSdpCallback)
    fun addIceCandidate(candidate: String)
    fun close()
}

interface LanPeerFactory {
    fun create(iceServers: List<String>, observer: LanPeerObserver): LanPeerConnection
}

class LanPeerEngine(
    private val signals: LanSignalSource,
    private val peerFactory: LanPeerFactory,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 8_000,
) : LanPeerObserver, LanDataChannelObserver {
    private val mutableState = MutableStateFlow<LanPeerState>(LanPeerState.Idle)
    val state: StateFlow<LanPeerState> = mutableState.asStateFlow()
    private val closed = AtomicBoolean(false)
    private var peer: LanPeerConnection? = null
    private var channel: LanDataChannel? = null
    private var signalJob: Job? = null
    private var timeoutJob: Job? = null
    private val bufferedChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var activeFileId: String? = null
    private val incomingMessages = Channel<LanChannelMessage>(Channel.UNLIMITED)
    val messages: Flow<LanChannelMessage> = incomingMessages.receiveAsFlow()

    fun start() {
        if (closed.get() || signalJob != null) return
        peer = peerFactory.create(emptyList(), this)
        mutableState.value = LanPeerState.Waiting
        signalJob = scope.launch {
            signals.events.collect { event ->
                when (event) {
                    is LanSignalEvent.Offer -> acceptOffer(event.sdp)
                    is LanSignalEvent.Ice -> if (isHostCandidate(event.candidate)) peer?.addIceCandidate(event.candidate)
                    is LanSignalEvent.PeerOffline -> fail("LAN_PEER_OFFLINE")
                    is LanSignalEvent.Error -> fail(event.code)
                    else -> Unit
                }
            }
        }
    }

    private fun acceptOffer(sdp: String) {
        if (closed.get()) return
        mutableState.value = LanPeerState.Connecting
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            if (mutableState.value == LanPeerState.Connecting) fail("LAN_DIRECT_TIMEOUT")
        }
        peer?.setRemoteOffer(sdp, callback(
            success = { peer?.createAnswer(callback(success = ::publishAnswer)) },
        ))
    }

    private fun publishAnswer(sdp: String) {
        peer?.setLocalAnswer(sdp, callback(success = {
            if (!signals.sendAnswer(sdp)) fail("LAN_SIGNAL_SEND_FAILED")
        }))
    }

    override fun onIceCandidate(candidate: String) {
        if (isHostCandidate(candidate)) signals.sendIce(candidate)
    }

    override fun onDataChannel(channel: LanDataChannel) {
        if (!channel.isOrdered) {
            channel.close()
            fail("LAN_DATA_CHANNEL_UNORDERED")
            return
        }
        this.channel?.close()
        this.channel = channel
        channel.setObserver(this)
        if (channel.isOpen) onOpen()
    }

    override fun onOpen() {
        if (closed.get()) return
        timeoutJob?.cancel()
        mutableState.value = LanPeerState.Connected
        if (!signals.markConnected()) fail("LAN_SIGNAL_SEND_FAILED")
    }

    override fun onBufferedAmountChange(previousAmount: Long) {
        if (channel?.bufferedAmount?.let { it <= LAN_BUFFER_LOW_BYTES } == true) bufferedChanges.tryEmit(Unit)
    }

    override fun onText(text: String) {
        incomingMessages.trySend(LanChannelMessage.Control(text))
    }

    override fun onBinary(bytes: ByteArray) {
        incomingMessages.trySend(LanChannelMessage.Binary(bytes))
    }

    @Synchronized
    fun beginFile(fileId: String): Boolean {
        if (fileId.isBlank() || mutableState.value != LanPeerState.Connected || activeFileId != null) return false
        activeFileId = fileId
        return true
    }

    @Synchronized
    fun finishFile(fileId: String) {
        if (activeFileId != fileId) throw LanProtocolException("LAN_PROTOCOL_ERROR")
        activeFileId = null
    }

    suspend fun sendBinary(bytes: ByteArray): Boolean {
        val current = channel ?: return false
        if (!current.isOpen || bytes.size > LAN_CHUNK_BYTES) return false
        if (current.bufferedAmount > LAN_BUFFER_HIGH_BYTES) {
            bufferedChanges.first { current !== channel || !current.isOpen || current.bufferedAmount <= LAN_BUFFER_LOW_BYTES }
        }
        return current === channel && current.isOpen && current.sendBinary(bytes)
    }

    fun sendControl(frame: LanControlFrame): Boolean =
        channel?.takeIf { it.isOpen }?.sendText(LanProtocol.encode(frame)) == true

    override fun onClosed() {
        if (!closed.get()) fail("LAN_DATA_CHANNEL_CLOSED")
    }

    override fun onFailed(code: String) = fail(code)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        signalJob?.cancel()
        channel?.close()
        peer?.close()
        activeFileId = null
        incomingMessages.close()
        mutableState.value = LanPeerState.Closed
    }

    private fun fail(code: String) {
        if (closed.get() || mutableState.value is LanPeerState.Failed) return
        mutableState.value = LanPeerState.Failed(code)
        timeoutJob?.cancel()
        activeFileId = null
        val activeChannel = channel
        channel = null
        activeChannel?.close()
        peer?.close()
        peer = null
        bufferedChanges.tryEmit(Unit)
    }

    private fun callback(success: (String) -> Unit): LanSdpCallback = object : LanSdpCallback {
        override fun onSuccess(sdp: String) = success(sdp)
        override fun onFailure(code: String) = fail(code)
    }
}
