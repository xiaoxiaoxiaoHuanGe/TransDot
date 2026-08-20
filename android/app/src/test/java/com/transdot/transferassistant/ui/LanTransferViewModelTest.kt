package com.transdot.transferassistant.ui

import com.transdot.transferassistant.lan.LAN_CHUNK_BYTES
import com.transdot.transferassistant.lan.LanChannelMessage
import com.transdot.transferassistant.lan.LanControlFrame
import com.transdot.transferassistant.lan.LanFileMetadata
import com.transdot.transferassistant.lan.LanPeerState
import com.transdot.transferassistant.lan.LanTransferPeer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanTransferViewModelTest {
    @Test
    fun reflectsWaitingConnectingAndConnectedWithoutCloudFallback() = runTest {
        val peer = FakeTransferPeer()
        val foreground = FakeForegroundController()
        val model = model(peer, FakeFileAccess(), foreground)

        peer.state.value = LanPeerState.Waiting
        peer.state.value = LanPeerState.Connecting
        peer.state.value = LanPeerState.Connected
        advanceUntilIdle()

        assertEquals(LanConnectionStatus.Connected, model.uiState.value.connection)
        assertEquals(0, foreground.starts)
        assertFalse(peer.cloudFallbackCalled)
    }

    @Test
    fun sendsMultipleFilesSequentiallyAndRunsServiceOnlyWhileActive() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess().apply {
            add("first", "一.txt", byteArrayOf(1, 2, 3))
            add("second", "two.bin", ByteArray(LAN_CHUNK_BYTES + 1) { 7 })
        }
        val foreground = FakeForegroundController()
        var now = 1_000L
        val model = model(peer, files, foreground) { now }

        model.enqueue(listOf("first", "second"))
        advanceUntilIdle()
        val firstId = model.uiState.value.items.first().id
        assertTrue(peer.controls.last() is LanControlFrame.FileOffer)
        peer.emit(LanControlFrame.FileAccept(firstId))
        now += 1_000
        advanceUntilIdle()
        assertEquals(1, peer.binary.size)
        assertTrue(peer.controls.last() is LanControlFrame.FileComplete)
        assertEquals(1, foreground.starts)
        peer.emit(LanControlFrame.FileVerified(firstId))
        advanceUntilIdle()

        assertEquals(LanTransferStatus.Completed, model.uiState.value.items.first().status)
        assertEquals("two.bin", (peer.controls.last() as LanControlFrame.FileOffer).name)
        assertEquals(1, foreground.stops)
        assertFalse(peer.cloudFallbackCalled)
    }

    @Test
    fun automaticallyReceivesAndVerifiesAnIncomingFile() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess()
        val foreground = FakeForegroundController()
        val model = model(peer, files, foreground)
        val bytes = "abc".toByteArray()

        peer.emit(LanControlFrame.FileOffer("incoming", "../中文.txt", "text/plain", bytes.size.toLong()))
        peer.emitBinary(bytes)
        peer.emit(LanControlFrame.FileComplete("incoming", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        advanceUntilIdle()

        assertTrue(peer.controls.any { it == LanControlFrame.FileAccept("incoming") })
        assertTrue(peer.controls.any { it == LanControlFrame.FileVerified("incoming") })
        assertEquals("中文.txt", files.completed.single().first)
        assertTrue(files.completed.single().second.contentEquals(bytes))
        assertEquals(LanTransferStatus.Completed, model.uiState.value.items.single().status)
        assertEquals(1, foreground.starts)
        assertEquals(1, foreground.stops)
    }

    @Test
    fun destinationFailureRejectsIncomingAndNeverStartsService() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess().apply { destinationAvailable = false }
        val foreground = FakeForegroundController()
        val model = model(peer, files, foreground)

        peer.emit(LanControlFrame.FileOffer("incoming", "file.bin", "application/octet-stream", 4))
        advanceUntilIdle()

        assertEquals(LanControlFrame.FileReject("incoming", "DESTINATION_UNAVAILABLE"), peer.controls.last())
        assertEquals(LanTransferStatus.Failed, model.uiState.value.items.single().status)
        assertEquals(0, foreground.starts)
    }

    @Test
    fun cancelAndRetryCleanupWithoutUploadingToTimeline() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess().apply { add("first", "first.bin", byteArrayOf(1, 2)) }
        val foreground = FakeForegroundController()
        val model = model(peer, files, foreground)

        model.enqueue(listOf("first"))
        advanceUntilIdle()
        val id = model.uiState.value.items.single().id
        model.cancelActive()
        advanceUntilIdle()
        assertEquals(LanTransferStatus.Cancelled, model.uiState.value.items.single().status)
        assertTrue(peer.controls.contains(LanControlFrame.TransferCancel(id)))

        model.retry(id)
        advanceUntilIdle()
        assertEquals(LanTransferStatus.Transferring, model.uiState.value.items.single().status)
        assertTrue(peer.controls.last() is LanControlFrame.FileOffer)
        assertFalse(peer.cloudFallbackCalled)
    }

    @Test
    fun peerFailureDeletesPartialReceiveAndStopsService() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess()
        val foreground = FakeForegroundController()
        val model = model(peer, files, foreground)

        peer.emit(LanControlFrame.FileOffer("incoming", "partial.bin", "application/octet-stream", 4))
        peer.emitBinary(byteArrayOf(1, 2))
        advanceUntilIdle()
        peer.state.value = LanPeerState.Failed("LAN_PEER_OFFLINE")
        advanceUntilIdle()

        assertEquals(LanTransferStatus.Failed, model.uiState.value.items.single().status)
        assertEquals(1, files.cancelled)
        assertEquals(1, foreground.stops)
        assertFalse(peer.cloudFallbackCalled)
    }

    @Test
    fun sendsQueueCompleteAfterTheLastVerifiedFile() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess().apply { add("only", "only.bin", byteArrayOf(1)) }
        val model = model(peer, files, FakeForegroundController())

        model.enqueue(listOf("only"))
        advanceUntilIdle()
        val id = model.uiState.value.items.single().id
        peer.emit(LanControlFrame.FileAccept(id))
        advanceUntilIdle()
        peer.emit(LanControlFrame.FileVerified(id))
        advanceUntilIdle()

        assertEquals(LanControlFrame.QueueComplete, peer.controls.last())
    }

    @Test
    fun foregroundStartFailureCleansIncomingDestination() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess()
        val foreground = FakeForegroundController().apply { failStart = true }
        val model = model(peer, files, foreground)

        peer.emit(LanControlFrame.FileOffer("incoming", "file.bin", "application/octet-stream", 2))
        advanceUntilIdle()

        assertEquals(LanTransferStatus.Failed, model.uiState.value.items.single().status)
        assertEquals(1, files.cancelled)
        assertEquals(null, peer.active)
    }

    @Test
    fun failedFileCannotBeRetriedIntoAFullQueue() = runTest {
        val peer = FakeTransferPeer().apply { state.value = LanPeerState.Connected }
        val files = FakeFileAccess().apply {
            add("failed", "failed.bin", byteArrayOf(1))
            repeat(20) { add("queued-$it", "$it.bin", byteArrayOf(1)) }
        }
        val model = model(peer, files, FakeForegroundController())
        model.enqueue(listOf("failed")); advanceUntilIdle()
        val failedId = model.uiState.value.items.single().id
        model.cancelActive(); advanceUntilIdle()
        model.enqueue(List(20) { "queued-$it" }); advanceUntilIdle()

        model.retry(failedId)
        advanceUntilIdle()

        assertEquals("TOO_MANY_FILES", model.uiState.value.error)
        assertEquals(LanTransferStatus.Cancelled, model.uiState.value.items.first().status)
    }

    @Test
    fun failedFileAcceptCleansDestinationAndStopsForegroundService() = runTest {
        val peer = FakeTransferPeer().apply {
            state.value = LanPeerState.Connected
            rejectedControl = LanControlFrame.FileAccept("incoming")
        }
        val files = FakeFileAccess()
        val foreground = FakeForegroundController()
        val model = model(peer, files, foreground)

        peer.emit(LanControlFrame.FileOffer("incoming", "file.bin", "application/octet-stream", 2))
        advanceUntilIdle()

        assertEquals(LanTransferStatus.Failed, model.uiState.value.items.single().status)
        assertEquals(1, files.cancelled)
        assertEquals(1, foreground.stops)
    }

    @Test
    fun cancelNeverPlacesBinaryAfterCancelControl() = runTest {
        val peer = FakeTransferPeer().apply {
            state.value = LanPeerState.Connected
            binaryGate = CompletableDeferred()
        }
        val files = FakeFileAccess().apply { add("first", "first.bin", byteArrayOf(1, 2)) }
        val model = model(peer, files, FakeForegroundController())
        model.enqueue(listOf("first")); advanceUntilIdle()
        val id = model.uiState.value.items.single().id
        peer.emit(LanControlFrame.FileAccept(id)); advanceUntilIdle()

        model.cancelActive()
        advanceUntilIdle()

        val cancelIndex = peer.events.indexOf("control:transfer_cancel")
        assertTrue(cancelIndex >= 0)
        assertFalse(peer.events.drop(cancelIndex + 1).contains("binary"))
    }

    private fun TestScope.model(
        peer: FakeTransferPeer,
        files: FakeFileAccess,
        foreground: FakeForegroundController,
        now: () -> Long = { 1_000L },
    ): LanTransferViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return LanTransferViewModel(
            peer = peer,
            files = files,
            foreground = foreground,
            stateDispatcher = dispatcher,
            fileDispatcher = dispatcher,
            now = now,
        ) { "id-${peer.nextId++}" }
    }
}

private class FakeTransferPeer : LanTransferPeer {
    override val state = MutableStateFlow<LanPeerState>(LanPeerState.Waiting)
    private val mutableMessages = MutableSharedFlow<LanChannelMessage>(extraBufferCapacity = 32)
    override val messages: Flow<LanChannelMessage> = mutableMessages
    val controls = mutableListOf<LanControlFrame>()
    val binary = mutableListOf<ByteArray>()
    val events = mutableListOf<String>()
    var binaryGate: CompletableDeferred<Unit>? = null
    var rejectedControl: LanControlFrame? = null
    var active: String? = null
    var nextId = 1
    var cloudFallbackCalled = false
    override fun start() = Unit
    override fun beginFile(fileId: String): Boolean { if (active != null) return false; active = fileId; return true }
    override fun finishFile(fileId: String) { if (active == fileId) active = null }
    override fun sendControl(frame: LanControlFrame): Boolean {
        val type = org.json.JSONObject(com.transdot.transferassistant.lan.LanProtocol.encode(frame)).getString("type")
        events += "control:$type"
        if (frame == rejectedControl) return false
        return controls.add(frame)
    }
    override suspend fun sendBinary(bytes: ByteArray): Boolean {
        binaryGate?.await()
        events += "binary"
        return binary.add(bytes.copyOf())
    }
    override fun reconnect(): Boolean { state.value = LanPeerState.Waiting; return true }
    override fun close() = Unit
    fun emit(frame: LanControlFrame) { mutableMessages.tryEmit(LanChannelMessage.Control(com.transdot.transferassistant.lan.LanProtocol.encode(frame))) }
    fun emitBinary(bytes: ByteArray) { mutableMessages.tryEmit(LanChannelMessage.Binary(bytes)) }
}

private class FakeFileAccess : LanTransferFiles {
    private val sources = mutableMapOf<String, Pair<LanFileMetadata, ByteArray>>()
    val completed = mutableListOf<Pair<String, ByteArray>>()
    var cancelled = 0
    var destinationAvailable = true
    fun add(id: String, name: String, bytes: ByteArray) {
        sources[id] = LanFileMetadata(id, name, "application/octet-stream", bytes.size.toLong()) to bytes
    }
    override fun inspect(sourceId: String) = requireNotNull(sources[sourceId]).first
    override fun openSource(sourceId: String) = ByteArrayInputStream(requireNotNull(sources[sourceId]).second)
    override fun openDestination(name: String, mime: String, size: Long): LanTransferDestination {
        if (!destinationAvailable) error("DESTINATION_UNAVAILABLE")
        val output = ByteArrayOutputStream()
        return object : LanTransferDestination {
            override fun write(bytes: ByteArray) = output.write(bytes)
            override fun verify(sha256: String): Boolean {
                val actual = com.transdot.transferassistant.lan.sha256Hex(output.toByteArray())
                if (actual != sha256) return false
                completed += name to output.toByteArray()
                return true
            }
            override fun cancel() { cancelled += 1 }
        }
    }
}

private class FakeForegroundController : LanForegroundController {
    var starts = 0
    var stops = 0
    var failStart = false
    override fun start(direction: LanTransferDirection, filename: String, progress: Int, onCancel: () -> Unit) {
        if (failStart) error("FOREGROUND_SERVICE_UNAVAILABLE")
        starts += 1
    }
    override fun update(direction: LanTransferDirection, filename: String, progress: Int) = Unit
    override fun stop() { stops += 1 }
}
