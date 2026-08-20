package com.transdot.transferassistant.lan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanPeerEngineTest {
    @Test
    fun acceptsOfferCreatesAnswerAndUsesEmptyIceAndOrderedChannel() = runTest {
        val signals = FakeLanSignals()
        val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope, timeoutMillis = 8_000)
        val states = mutableListOf<LanPeerState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.state.toList(states) }

        engine.start()
        runCurrent()
        signals.emit(LanSignalEvent.PeerOnline("session"))
        signals.emit(LanSignalEvent.Offer("session", "offer-sdp"))
        runCurrent()
        assertEquals(emptyList<String>(), factory.iceServers)
        assertEquals("offer-sdp", factory.peer.remoteSdp)
        factory.peer.answerCallback!!.onSuccess("answer-sdp")
        assertEquals("answer-sdp", signals.answer)
        assertTrue(states.contains(LanPeerState.Connecting))

        factory.peer.emitDataChannel()
        (factory.peer.channel as FakeChannel).open()
        assertTrue(states.contains(LanPeerState.Connected))
        assertTrue(signals.connected)
    }

    @Test
    fun ignoresNonHostIceAndFailsAfterEightSecondsWithoutFallback() = runTest {
        val signals = FakeLanSignals()
        val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope, timeoutMillis = 8_000)
        engine.start()
        runCurrent()
        signals.emit(LanSignalEvent.PeerOnline("session"))
        signals.emit(LanSignalEvent.Offer("session", "offer"))
        signals.emit(LanSignalEvent.Ice("session", LanIceCandidate("candidate typ srflx", "0", 0)))
        signals.emit(LanSignalEvent.Ice("session", LanIceCandidate("candidate typ host", "0", 0)))
        runCurrent()
        assertEquals(listOf(LanIceCandidate("candidate typ host", "0", 0)), factory.peer.addedCandidates)
        advanceTimeBy(8_001)
        assertEquals(LanPeerState.Failed("LAN_DIRECT_TIMEOUT"), engine.state.value)
        assertFalse(signals.fallbackCalled)
    }

    @Test
    fun queuesRemoteIceUntilOfferIsSet() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        engine.start(); runCurrent()
        factory.peer.autoCompleteRemoteOffer = false
        val candidate = LanIceCandidate("candidate typ host", "0", 0)

        signals.emit(LanSignalEvent.PeerOnline("session"))
        signals.emit(LanSignalEvent.Ice("session", candidate))
        signals.emit(LanSignalEvent.Offer("session", "offer"))
        runCurrent()
        assertEquals(emptyList<LanIceCandidate>(), factory.peer.addedCandidates)

        factory.peer.completeRemoteOffer()
        assertEquals(listOf(candidate), factory.peer.addedCandidates)
    }

    @Test
    fun ignoresOldSessionIceAndDeferredCallbacksAfterReconnect() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope, timeoutMillis = 100)
        engine.start(); runCurrent()
        factory.peer.autoCompleteRemoteOffer = false
        signals.emit(LanSignalEvent.PeerOnline("old"))
        signals.emit(LanSignalEvent.Offer("old", "old-offer"))
        runCurrent()
        val oldPeer = factory.peer
        advanceTimeBy(101)
        assertTrue(engine.reconnect())
        val newPeer = factory.peer
        signals.emit(LanSignalEvent.PeerOnline("new"))
        signals.emit(LanSignalEvent.Ice("old", LanIceCandidate("candidate typ host", "0", 0)))
        runCurrent()

        oldPeer.completeRemoteOffer()

        assertTrue(oldPeer !== newPeer)
        assertEquals(emptyList<LanIceCandidate>(), newPeer.addedCandidates)
        assertEquals(null, newPeer.answerCallback)
        assertEquals(LanPeerState.Waiting, engine.state.value)
    }

    @Test
    fun ignoresOldDataChannelCallbacksAfterReconnect() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        engine.start(); runCurrent()
        factory.peer.emitDataChannel()
        val oldChannel = factory.peer.channel as FakeChannel
        engine.onFailed("LAN_PEER_OFFLINE")
        assertTrue(engine.reconnect())

        oldChannel.open()

        assertEquals(LanPeerState.Waiting, engine.state.value)
        assertFalse(signals.connected)
    }

    @Test
    fun closeIsIdempotentAndClosesPeerOnce() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        engine.start(); engine.close(); engine.close()
        assertEquals(1, factory.peer.closeCount)
        assertEquals(LanPeerState.Closed, engine.state.value)
    }

    @Test
    fun appliesBackpressureAndAllowsOnlyOneActiveFile() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        engine.start(); runCurrent()
        factory.peer.emitDataChannel(); val channel = factory.peer.channel as FakeChannel
        channel.open()
        assertTrue(engine.beginFile("first"))
        assertFalse(engine.beginFile("second"))

        channel.buffered = LAN_BUFFER_HIGH_BYTES + 1
        var sent: Boolean? = null
        backgroundScope.launch { sent = engine.sendBinary(byteArrayOf(1, 2, 3)) }
        runCurrent()
        assertEquals(null, sent)
        assertTrue(channel.binary.isEmpty())
        channel.buffered = LAN_BUFFER_LOW_BYTES
        channel.bufferedAmountChanged(LAN_BUFFER_HIGH_BYTES + 1)
        runCurrent()
        assertTrue(sent == true)
        assertEquals(1, channel.binary.size)
        engine.finishFile("first")
        assertTrue(engine.beginFile("second"))
    }

    @Test
    fun disconnectCleansUpPeerAndReleasesBackpressuredSend() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        engine.start(); runCurrent()
        factory.peer.emitDataChannel(); val channel = factory.peer.channel as FakeChannel
        channel.open(); channel.buffered = LAN_BUFFER_HIGH_BYTES + 1
        var sent: Boolean? = null
        backgroundScope.launch { sent = engine.sendBinary(byteArrayOf(1)) }
        runCurrent()

        channel.close(); runCurrent()
        assertEquals(false, sent)
        assertEquals(1, factory.peer.closeCount)
        assertEquals(LanPeerState.Failed("LAN_DATA_CHANNEL_CLOSED"), engine.state.value)
    }

    @Test
    fun exposesControlAndBinaryMessagesWithoutCombiningFileChunks() = runTest {
        val signals = FakeLanSignals(); val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        val messages = mutableListOf<LanChannelMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { engine.messages.toList(messages) }
        engine.start(); runCurrent()
        factory.peer.emitDataChannel(); val channel = factory.peer.channel as FakeChannel
        channel.open()
        channel.text("{\"type\":\"queue_complete\"}")
        channel.bytes(byteArrayOf(4, 5))
        assertEquals(LanChannelMessage.Control("{\"type\":\"queue_complete\"}"), messages[0])
        assertTrue((messages[1] as LanChannelMessage.Binary).bytes.contentEquals(byteArrayOf(4, 5)))
    }

    @Test
    fun reconnectAfterFailureCreatesAFreshPeerAndReannouncesReady() = runTest {
        val signals = FakeLanSignals()
        val factory = FakeLanPeerFactory()
        val engine = LanPeerEngine(signals, factory, backgroundScope)
        engine.start()
        engine.onFailed("LAN_PEER_OFFLINE")

        assertTrue(engine.reconnect())

        assertEquals(2, factory.createCount)
        assertTrue(signals.restarted)
        assertEquals(LanPeerState.Waiting, engine.state.value)
    }
}

private class FakeLanSignals : LanSignalSource {
    override val events = MutableSharedFlow<LanSignalEvent>(extraBufferCapacity = 16)
    var answer: String? = null; var connected = false; var fallbackCalled = false
    var restarted = false
    override fun sendAnswer(sdp: String): Boolean { answer = sdp; return true }
    override fun sendIce(candidate: LanIceCandidate): Boolean = true
    override fun markConnected(): Boolean { connected = true; return true }
    override fun restartSession(): Boolean { restarted = true; return true }
    suspend fun emit(event: LanSignalEvent) { events.emit(event) }
}

private class FakeLanPeerFactory : LanPeerFactory {
    val iceServers = mutableListOf<String>()
    val peers = mutableListOf<FakePeer>()
    val peer get() = peers.last()
    var createCount = 0
    override fun create(iceServers: List<String>, observer: LanPeerObserver): LanPeerConnection {
        createCount += 1
        this.iceServers += iceServers
        return FakePeer().also { it.observer = observer; peers += it }
    }
}

private class FakePeer : LanPeerConnection {
    var observer: LanPeerObserver? = null; var remoteSdp: String? = null
    var answerCallback: LanSdpCallback? = null; var channel: LanDataChannel? = null
    var closeCount = 0
    val addedCandidates = mutableListOf<LanIceCandidate>()
    var autoCompleteRemoteOffer = true
    private var remoteOfferCallback: LanSdpCallback? = null
    override fun setRemoteOffer(sdp: String, callback: LanSdpCallback) {
        remoteSdp = sdp
        remoteOfferCallback = callback
        if (autoCompleteRemoteOffer) completeRemoteOffer()
    }
    fun completeRemoteOffer() { remoteOfferCallback?.onSuccess(requireNotNull(remoteSdp)); remoteOfferCallback = null }
    override fun createAnswer(callback: LanSdpCallback) { answerCallback = callback }
    override fun setLocalAnswer(sdp: String, callback: LanSdpCallback) { callback.onSuccess(sdp) }
    override fun addIceCandidate(candidate: LanIceCandidate): Boolean { addedCandidates += candidate; return true }
    override fun close() { closeCount++ }
    fun emitDataChannel() { channel = FakeChannel().also { observer?.onDataChannel(it) } }
}
private class FakeChannel : LanDataChannel {
    private var observer: LanDataChannelObserver? = null
    override val isOpen get() = opened
    override val isOrdered get() = true
    override val bufferedAmount get() = buffered
    var buffered = 0L
    val binary = mutableListOf<ByteArray>()
    var opened = false
    override fun setObserver(observer: LanDataChannelObserver) { this.observer = observer }
    override fun sendText(text: String) = true
    override fun sendBinary(bytes: ByteArray): Boolean { binary += bytes; return true }
    override fun close() { opened = false; observer?.onClosed() }
    fun open() { opened = true; observer?.onOpen() }
    fun bufferedAmountChanged(previous: Long) { observer?.onBufferedAmountChange(previous) }
    fun text(value: String) { observer?.onText(value) }
    fun bytes(value: ByteArray) { observer?.onBinary(value) }
}
