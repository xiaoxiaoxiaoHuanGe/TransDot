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
    fun sendIce(candidate: LanIceCandidate): Boolean
    fun markConnected(): Boolean
    fun restartSession(): Boolean
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
    fun onIceCandidate(candidate: LanIceCandidate)
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
    fun addIceCandidate(candidate: LanIceCandidate): Boolean
    fun close()
}

interface LanPeerFactory {
    fun create(iceServers: List<String>, observer: LanPeerObserver): LanPeerConnection
}

interface LanTransferPeer {
    val state: StateFlow<LanPeerState>
    val messages: Flow<LanChannelMessage>
    fun start()
    fun beginFile(fileId: String): Boolean
    fun finishFile(fileId: String)
    fun sendControl(frame: LanControlFrame): Boolean
    suspend fun sendBinary(bytes: ByteArray): Boolean
    fun reconnect(): Boolean
    fun close()
}

class LanPeerEngine(
    private val signals: LanSignalSource,
    private val peerFactory: LanPeerFactory,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 8_000,
) : LanTransferPeer, LanPeerObserver, LanDataChannelObserver {
    private val mutableState = MutableStateFlow<LanPeerState>(LanPeerState.Idle)
    override val state: StateFlow<LanPeerState> = mutableState.asStateFlow()
    private val closed = AtomicBoolean(false)
    private var peer: LanPeerConnection? = null
    private var channel: LanDataChannel? = null
    private var signalJob: Job? = null
    private var timeoutJob: Job? = null
    private val bufferedChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var activeFileId: String? = null
    private val incomingMessages = Channel<LanChannelMessage>(Channel.UNLIMITED)
    private val pendingRemoteIce = mutableListOf<LanIceCandidate>()
    private var remoteDescriptionSet = false
    private var activeSessionId: String? = null
    private var peerGeneration = 0L
    override val messages: Flow<LanChannelMessage> = incomingMessages.receiveAsFlow()

    override fun start() {
        if (closed.get() || signalJob != null) return
        createPeer()
        mutableState.value = LanPeerState.Waiting
        signalJob = scope.launch {
            signals.events.collect { event ->
                when (event) {
                    is LanSignalEvent.PeerOnline -> setActiveSession(event.sessionId)
                    is LanSignalEvent.Offer -> if (isActiveSession(event.sessionId)) acceptOffer(event.sdp)
                    is LanSignalEvent.Ice -> if (isActiveSession(event.sessionId)) acceptIce(event.ice)
                    is LanSignalEvent.PeerOffline -> if (clearActiveSession(event.sessionId)) {
                        fail("LAN_PEER_OFFLINE")
                    }
                    is LanSignalEvent.Error -> fail(event.code)
                    else -> Unit
                }
            }
        }
    }

    private fun acceptOffer(sdp: String) {
        if (closed.get()) return
        val (current, generation) = synchronized(this) { (peer ?: return) to peerGeneration }
        mutableState.value = LanPeerState.Connecting
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMillis)
            if (mutableState.value == LanPeerState.Connecting) fail("LAN_DIRECT_TIMEOUT")
        }
        current.setRemoteOffer(sdp, callback(valid = { isCurrent(current, generation) }, success = {
            val pending = synchronized(this) {
                if (!isCurrentLocked(current, generation)) return@callback
                remoteDescriptionSet = true
                pendingRemoteIce.toList().also { pendingRemoteIce.clear() }
            }
            if (pending.any { !addIceIfCurrent(current, generation, it) }) {
                if (isCurrent(current, generation)) fail("LAN_NEGOTIATION_FAILED")
                return@callback
            }
            current.createAnswer(callback(valid = { isCurrent(current, generation) }, success = {
                publishAnswer(current, generation, it)
            }))
        }))
    }

    @Synchronized
    private fun acceptIce(ice: LanIceCandidate) {
        if (!isHostCandidate(ice.candidate)) return
        val current = peer ?: return
        if (!remoteDescriptionSet) pendingRemoteIce += ice
        else if (!current.addIceCandidate(ice)) fail("LAN_NEGOTIATION_FAILED")
    }

    private fun publishAnswer(current: LanPeerConnection, generation: Long, sdp: String) {
        current.setLocalAnswer(sdp, callback(valid = { isCurrent(current, generation) }, success = {
            if (!signals.sendAnswer(sdp)) fail("LAN_SIGNAL_SEND_FAILED")
        }))
    }

    private fun createPeer() {
        val generation = synchronized(this) { ++peerGeneration }
        val observer = object : LanPeerObserver {
            override fun onIceCandidate(candidate: LanIceCandidate) {
                sendIceIfCurrent(generation, candidate)
            }
            override fun onDataChannel(channel: LanDataChannel) {
                attachDataChannel(channel, generation)
            }
            override fun onFailed(code: String) {
                failIfCurrent(generation, code)
            }
        }
        val created = peerFactory.create(emptyList(), observer)
        synchronized(this) {
            if (!closed.get() && generation == peerGeneration) peer = created else created.close()
        }
    }

    @Synchronized
    private fun isCurrent(current: LanPeerConnection, generation: Long) = isCurrentLocked(current, generation)

    private fun isCurrentLocked(current: LanPeerConnection, generation: Long) =
        !closed.get() && peer === current && peerGeneration == generation

    @Synchronized
    private fun isCurrentGeneration(generation: Long) = !closed.get() && peerGeneration == generation

    private fun isCurrentChannel(current: LanDataChannel, generation: Long) =
        !closed.get() && peerGeneration == generation && channel === current

    @Synchronized
    private fun sendIceIfCurrent(generation: Long, candidate: LanIceCandidate) {
        if (isCurrentGeneration(generation) && isHostCandidate(candidate.candidate)) signals.sendIce(candidate)
    }

    @Synchronized
    private fun failIfCurrent(generation: Long, code: String) {
        if (isCurrentGeneration(generation)) fail(code)
    }

    @Synchronized
    private fun setActiveSession(sessionId: String) { activeSessionId = sessionId }

    @Synchronized
    private fun isActiveSession(sessionId: String) = activeSessionId == sessionId

    @Synchronized
    private fun clearActiveSession(sessionId: String?): Boolean {
        if (sessionId == null || sessionId != activeSessionId) return false
        activeSessionId = null
        return true
    }

    @Synchronized
    private fun addIceIfCurrent(current: LanPeerConnection, generation: Long, ice: LanIceCandidate) =
        isCurrentLocked(current, generation) && current.addIceCandidate(ice)

    override fun onIceCandidate(candidate: LanIceCandidate) {
        sendIceIfCurrent(peerGeneration, candidate)
    }

    override fun onDataChannel(channel: LanDataChannel) {
        attachDataChannel(channel, peerGeneration)
    }

    @Synchronized
    private fun attachDataChannel(channel: LanDataChannel, generation: Long) {
        if (!isCurrentGeneration(generation)) {
            channel.close()
            return
        }
        if (!channel.isOrdered) {
            channel.close()
            fail("LAN_DATA_CHANNEL_UNORDERED")
            return
        }
        val previous = this.channel
        this.channel = channel
        channel.setObserver(object : LanDataChannelObserver {
            override fun onOpen() = openIfCurrent(channel, generation)
            override fun onText(text: String) = textIfCurrent(channel, generation, text)
            override fun onBinary(bytes: ByteArray) = binaryIfCurrent(channel, generation, bytes)
            override fun onBufferedAmountChange(previousAmount: Long) = bufferIfCurrent(channel, generation)
            override fun onClosed() = closeIfCurrent(channel, generation)
        })
        previous?.close()
        if (channel.isOpen) openIfCurrent(channel, generation)
    }

    @Synchronized
    private fun openIfCurrent(channel: LanDataChannel, generation: Long) {
        if (!isCurrentChannel(channel, generation)) return
        timeoutJob?.cancel()
        mutableState.value = LanPeerState.Connected
        if (!signals.markConnected()) fail("LAN_SIGNAL_SEND_FAILED")
    }

    override fun onOpen() { channel?.let { openIfCurrent(it, peerGeneration) } }

    override fun onBufferedAmountChange(previousAmount: Long) {
        if (channel?.bufferedAmount?.let { it <= LAN_BUFFER_LOW_BYTES } == true) bufferedChanges.tryEmit(Unit)
    }

    @Synchronized
    private fun bufferIfCurrent(channel: LanDataChannel, generation: Long) {
        if (isCurrentChannel(channel, generation) && channel.bufferedAmount <= LAN_BUFFER_LOW_BYTES) bufferedChanges.tryEmit(Unit)
    }

    override fun onText(text: String) {
        incomingMessages.trySend(LanChannelMessage.Control(text))
    }

    @Synchronized
    private fun textIfCurrent(channel: LanDataChannel, generation: Long, text: String) {
        if (isCurrentChannel(channel, generation)) incomingMessages.trySend(LanChannelMessage.Control(text))
    }

    override fun onBinary(bytes: ByteArray) {
        incomingMessages.trySend(LanChannelMessage.Binary(bytes))
    }

    @Synchronized
    private fun binaryIfCurrent(channel: LanDataChannel, generation: Long, bytes: ByteArray) {
        if (isCurrentChannel(channel, generation)) incomingMessages.trySend(LanChannelMessage.Binary(bytes))
    }

    @Synchronized
    override fun beginFile(fileId: String): Boolean {
        if (fileId.isBlank() || mutableState.value != LanPeerState.Connected || activeFileId != null) return false
        activeFileId = fileId
        return true
    }

    @Synchronized
    override fun finishFile(fileId: String) {
        if (activeFileId != fileId) throw LanProtocolException("LAN_PROTOCOL_ERROR")
        activeFileId = null
    }

    override suspend fun sendBinary(bytes: ByteArray): Boolean {
        val current = channel ?: return false
        if (!current.isOpen || bytes.size > LAN_CHUNK_BYTES) return false
        if (current.bufferedAmount > LAN_BUFFER_HIGH_BYTES) {
            bufferedChanges.first { current !== channel || !current.isOpen || current.bufferedAmount <= LAN_BUFFER_LOW_BYTES }
        }
        return current === channel && current.isOpen && current.sendBinary(bytes)
    }

    override fun sendControl(frame: LanControlFrame): Boolean =
        channel?.takeIf { it.isOpen }?.sendText(LanProtocol.encode(frame)) == true

    @Synchronized
    override fun reconnect(): Boolean {
        if (closed.get() || mutableState.value !is LanPeerState.Failed) return false
        createPeer()
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        activeSessionId = null
        mutableState.value = LanPeerState.Waiting
        if (signals.restartSession()) return true
        fail("LAN_SIGNAL_SEND_FAILED")
        return false
    }

    override fun onClosed() {
        if (!closed.get()) fail("LAN_DATA_CHANNEL_CLOSED")
    }

    @Synchronized
    private fun closeIfCurrent(channel: LanDataChannel, generation: Long) {
        if (isCurrentChannel(channel, generation)) fail("LAN_DATA_CHANNEL_CLOSED")
    }

    override fun onFailed(code: String) = fail(code)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) { peerGeneration += 1 }
        timeoutJob?.cancel()
        signalJob?.cancel()
        channel?.close()
        peer?.close()
        activeFileId = null
        incomingMessages.close()
        mutableState.value = LanPeerState.Closed
    }

    @Synchronized
    private fun fail(code: String) {
        if (closed.get() || mutableState.value is LanPeerState.Failed) return
        peerGeneration += 1
        mutableState.value = LanPeerState.Failed(code)
        timeoutJob?.cancel()
        activeFileId = null
        val activeChannel = channel
        channel = null
        activeChannel?.close()
        peer?.close()
        peer = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        bufferedChanges.tryEmit(Unit)
    }

    private fun callback(valid: () -> Boolean = { true }, success: (String) -> Unit): LanSdpCallback = object : LanSdpCallback {
        override fun onSuccess(sdp: String) { if (valid()) success(sdp) }
        override fun onFailure(code: String) { if (valid()) fail(code) }
    }
}
