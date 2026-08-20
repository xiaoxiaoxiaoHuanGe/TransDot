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
        signals.emit(LanSignalEvent.Offer("session", "offer"))
        signals.emit(LanSignalEvent.Ice("session", "candidate typ srflx"))
        signals.emit(LanSignalEvent.Ice("session", "candidate typ host"))
        runCurrent()
        assertEquals(listOf("candidate typ host"), factory.peer.addedCandidates)
        advanceTimeBy(8_001)
        assertEquals(LanPeerState.Failed("LAN_DIRECT_TIMEOUT"), engine.state.value)
        assertFalse(signals.fallbackCalled)
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
}

private class FakeLanSignals : LanSignalSource {
    override val events = MutableSharedFlow<LanSignalEvent>(extraBufferCapacity = 16)
    var answer: String? = null; var connected = false; var fallbackCalled = false
    override fun sendAnswer(sdp: String): Boolean { answer = sdp; return true }
    override fun sendIce(candidate: String): Boolean = true
    override fun markConnected(): Boolean { connected = true; return true }
    suspend fun emit(event: LanSignalEvent) { events.emit(event) }
}

private class FakeLanPeerFactory : LanPeerFactory {
    val iceServers = mutableListOf<String>(); val peer = FakePeer()
    override fun create(iceServers: List<String>, observer: LanPeerObserver): LanPeerConnection {
        this.iceServers += iceServers; peer.observer = observer; return peer
    }
}

private class FakePeer : LanPeerConnection {
    var observer: LanPeerObserver? = null; var remoteSdp: String? = null
    var answerCallback: LanSdpCallback? = null; var channel: LanDataChannel? = null
    var closeCount = 0
    val addedCandidates = mutableListOf<String>()
    override fun setRemoteOffer(sdp: String, callback: LanSdpCallback) { remoteSdp = sdp; callback.onSuccess(sdp) }
    override fun createAnswer(callback: LanSdpCallback) { answerCallback = callback }
    override fun setLocalAnswer(sdp: String, callback: LanSdpCallback) { callback.onSuccess(sdp) }
    override fun addIceCandidate(candidate: String) { addedCandidates += candidate }
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
