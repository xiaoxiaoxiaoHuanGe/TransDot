package com.transdot.transferassistant.ui

import com.transdot.transferassistant.lan.LAN_CHUNK_BYTES
import com.transdot.transferassistant.lan.MAX_LAN_FILES
import com.transdot.transferassistant.lan.LanChannelMessage
import com.transdot.transferassistant.lan.LanControlFrame
import com.transdot.transferassistant.lan.LanFileMetadata
import com.transdot.transferassistant.lan.LanFileStore
import com.transdot.transferassistant.lan.LanPeerState
import com.transdot.transferassistant.lan.LanProtocol
import com.transdot.transferassistant.lan.LanTransferPeer
import com.transdot.transferassistant.lan.sanitizeLanFilename
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LanConnectionStatus { Waiting, Connecting, Connected, Failed, Closed }
enum class LanTransferDirection { Sending, Receiving }
enum class LanTransferStatus { Queued, Transferring, Completed, Failed, Cancelled }

data class LanTransferItem(
    val id: String,
    val sourceId: String? = null,
    val name: String,
    val mime: String,
    val size: Long,
    val direction: LanTransferDirection,
    val status: LanTransferStatus,
    val transferredBytes: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val error: String? = null,
) {
    val progress: Float get() = if (size == 0L && status == LanTransferStatus.Completed) 1f
        else if (size <= 0L) 0f else (transferredBytes.toDouble() / size).coerceIn(0.0, 1.0).toFloat()
}

data class LanTransferUiState(
    val connection: LanConnectionStatus = LanConnectionStatus.Waiting,
    val items: List<LanTransferItem> = emptyList(),
    val currentFileId: String? = null,
    val error: String? = null,
)

interface LanTransferDestination {
    fun write(bytes: ByteArray)
    fun verify(sha256: String): Boolean
    fun cancel()
}

interface LanTransferFiles {
    fun inspect(sourceId: String): LanFileMetadata
    fun openSource(sourceId: String): InputStream
    fun openDestination(name: String, mime: String, size: Long): LanTransferDestination
}

class StoredLanTransferFiles(private val store: LanFileStore) : LanTransferFiles {
    override fun inspect(sourceId: String) = store.inspect(sourceId)
    override fun openSource(sourceId: String) = store.openSource(sourceId).input
    override fun openDestination(name: String, mime: String, size: Long): LanTransferDestination {
        val destination = store.openDestination(name, mime, size)
        return object : LanTransferDestination {
            override fun write(bytes: ByteArray) = destination.write(bytes) {}
            override fun verify(sha256: String): Boolean = runCatching { destination.verify(sha256) }.isSuccess
            override fun cancel() = destination.cancel()
        }
    }
}

interface LanForegroundController {
    fun start(direction: LanTransferDirection, filename: String, progress: Int, onCancel: () -> Unit)
    fun update(direction: LanTransferDirection, filename: String, progress: Int)
    fun stop()
}

class LanTransferViewModel(
    private val peer: LanTransferPeer,
    private val files: LanTransferFiles,
    private val foreground: LanForegroundController,
    private val stateDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val fileDispatcher: CoroutineDispatcher = stateDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + stateDispatcher)
    private val mutableUiState = MutableStateFlow(LanTransferUiState())
    val uiState: StateFlow<LanTransferUiState> = mutableUiState.asStateFlow()
    private val closed = AtomicBoolean(false)
    @Volatile private var active: ActiveTransfer? = null
    private var sendJob: Job? = null
    private var sendGeneration = 0L
    private val sendMutex = Mutex()
    private var incomingOfferCount = 0
    private var outgoingBatchOpen = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { peer.state.collect(::onPeerState) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { peer.messages.collect(::onPeerMessage) }
        peer.start()
    }

    fun enqueue(sourceIds: List<String>) {
        scope.launch {
            val pendingCount = mutableUiState.value.items.count { it.status == LanTransferStatus.Queued || it.status == LanTransferStatus.Transferring }
            if (sourceIds.isEmpty()) return@launch
            if (pendingCount + sourceIds.size > MAX_LAN_FILES) return@launch setError("TOO_MANY_FILES")
            val additions = runCatching {
                sourceIds.map { sourceId ->
                    val metadata = files.inspect(sourceId)
                    LanTransferItem(
                        id = newId(), sourceId = sourceId, name = metadata.name, mime = metadata.mime,
                        size = metadata.size, direction = LanTransferDirection.Sending, status = LanTransferStatus.Queued,
                    )
                }
            }.getOrElse { return@launch setError(errorCode(it)) }
            outgoingBatchOpen = true
            update { it.copy(items = it.items + additions, error = null) }
            startNext()
        }
    }

    fun retry(fileId: String) {
        scope.launch {
            val item = mutableUiState.value.items.firstOrNull { it.id == fileId } ?: return@launch
            if (item.direction != LanTransferDirection.Sending || item.status !in setOf(LanTransferStatus.Failed, LanTransferStatus.Cancelled)) return@launch
            val activeCount = mutableUiState.value.items.count { it.status == LanTransferStatus.Queued || it.status == LanTransferStatus.Transferring }
            if (activeCount >= MAX_LAN_FILES) return@launch setError("TOO_MANY_FILES")
            outgoingBatchOpen = true
            replaceItem(item.copy(status = LanTransferStatus.Queued, transferredBytes = 0, speedBytesPerSecond = 0, error = null))
            startNext()
        }
    }

    fun cancelActive() {
        scope.launch { cancelActive("TRANSFER_CANCELLED", notifyPeer = true, cancelled = true) }
    }

    fun reconnect() {
        scope.launch {
            if (peer.reconnect()) update { it.copy(connection = LanConnectionStatus.Waiting, error = null) }
            else setError("LAN_RECONNECT_FAILED")
        }
    }

    fun clearError() = update { it.copy(error = null) }

    private fun onPeerState(state: LanPeerState) {
        val connection = when (state) {
            LanPeerState.Idle, LanPeerState.Waiting -> LanConnectionStatus.Waiting
            LanPeerState.Connecting -> LanConnectionStatus.Connecting
            LanPeerState.Connected -> LanConnectionStatus.Connected
            is LanPeerState.Failed -> LanConnectionStatus.Failed
            LanPeerState.Closed -> LanConnectionStatus.Closed
        }
        update { it.copy(connection = connection, error = (state as? LanPeerState.Failed)?.code ?: it.error) }
        if (state == LanPeerState.Connected) startNext()
        if (state is LanPeerState.Failed || state == LanPeerState.Waiting && active != null) {
            scope.launch { cancelActive((state as? LanPeerState.Failed)?.code ?: "LAN_PEER_OFFLINE", notifyPeer = false, cancelled = false) }
        }
    }

    private suspend fun onPeerMessage(message: LanChannelMessage) {
        when (message) {
            is LanChannelMessage.Binary -> receiveBinary(message.bytes)
            is LanChannelMessage.Control -> try {
                handleControl(LanProtocol.parse(message.text))
            } catch (_: Exception) {
                cancelActive("LAN_PROTOCOL_ERROR", notifyPeer = false, cancelled = false)
            }
        }
    }

    private suspend fun handleControl(frame: LanControlFrame) {
        when (frame) {
            is LanControlFrame.FileOffer -> acceptIncoming(frame)
            is LanControlFrame.FileAccept -> {
                val current = active as? ActiveTransfer.Outgoing ?: return
                if (current.item.id != frame.fileId || sendJob != null) return
                if (!startForeground(current.item)) {
                    failOutgoing(current.item.id, "FOREGROUND_SERVICE_UNAVAILABLE")
                    return
                }
                val generation = ++sendGeneration
                sendJob = scope.launch(fileDispatcher) { streamOutgoing(current, generation) }
            }
            is LanControlFrame.FileVerified -> completeOutgoing(frame.fileId)
            is LanControlFrame.FileReject -> failOutgoing(frame.fileId, frame.code)
            is LanControlFrame.FileFailed -> failOutgoing(frame.fileId, frame.code)
            is LanControlFrame.FileComplete -> completeIncoming(frame.fileId, frame.sha256)
            is LanControlFrame.TransferCancel -> if (active?.item?.id == frame.fileId) {
                cancelActive("TRANSFER_CANCELLED", notifyPeer = false, cancelled = true)
            }
            LanControlFrame.QueueComplete -> incomingOfferCount = 0
        }
    }

    private fun startNext() {
        if (closed.get() || active != null || mutableUiState.value.connection != LanConnectionStatus.Connected) return
        val next = mutableUiState.value.items.firstOrNull { it.direction == LanTransferDirection.Sending && it.status == LanTransferStatus.Queued }
        if (next == null) {
            if (outgoingBatchOpen) {
                peer.sendControl(LanControlFrame.QueueComplete)
                outgoingBatchOpen = false
            }
            return
        }
        if (!peer.beginFile(next.id)) return
        val transferring = next.copy(status = LanTransferStatus.Transferring)
        active = ActiveTransfer.Outgoing(transferring, now())
        replaceItem(transferring)
        update { it.copy(currentFileId = next.id) }
        if (!peer.sendControl(LanControlFrame.FileOffer(next.id, next.name, next.mime.ifBlank { "application/octet-stream" }, next.size))) {
            scope.launch { failOutgoing(next.id, "LAN_PEER_OFFLINE") }
        }
    }

    private fun acceptIncoming(offer: LanControlFrame.FileOffer) {
        if (incomingOfferCount >= MAX_LAN_FILES) {
            peer.sendControl(LanControlFrame.FileReject(offer.fileId, "TOO_MANY_FILES"))
            return
        }
        incomingOfferCount += 1
        if (active != null || !peer.beginFile(offer.fileId)) {
            peer.sendControl(LanControlFrame.FileReject(offer.fileId, "LAN_TRANSFER_BUSY"))
            return
        }
        val safeName = sanitizeLanFilename(offer.name)
        val item = LanTransferItem(
            id = offer.fileId, name = safeName, mime = offer.mime, size = offer.size,
            direction = LanTransferDirection.Receiving, status = LanTransferStatus.Transferring,
        )
        val destination = runCatching { files.openDestination(safeName, offer.mime, offer.size) }.getOrElse {
            runCatching { peer.finishFile(offer.fileId) }
            val failed = item.copy(status = LanTransferStatus.Failed, error = "DESTINATION_UNAVAILABLE")
            update { state -> state.copy(items = state.items + failed, error = "DESTINATION_UNAVAILABLE") }
            peer.sendControl(LanControlFrame.FileReject(offer.fileId, "DESTINATION_UNAVAILABLE"))
            return
        }
        active = ActiveTransfer.Incoming(item, now(), destination)
        update { it.copy(items = it.items + item, currentFileId = item.id, error = null) }
        if (!startForeground(item)) {
            scope.launch { cancelActive("FOREGROUND_SERVICE_UNAVAILABLE", notifyPeer = false, cancelled = false) }
            return
        }
        if (!peer.sendControl(LanControlFrame.FileAccept(item.id))) {
            scope.launch { cancelActive("LAN_PEER_OFFLINE", notifyPeer = false, cancelled = false) }
        }
    }

    private suspend fun streamOutgoing(current: ActiveTransfer.Outgoing, generation: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            files.openSource(requireNotNull(current.item.sourceId)).use { input ->
                current.input = input
                val buffer = ByteArray(LAN_CHUNK_BYTES)
                while (true) {
                    if (active !== current) return
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (active !== current) return
                    val chunk = if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count)
                    val sent = sendMutex.withLock { active === current && peer.sendBinary(chunk) }
                    if (!sent) {
                        if (active !== current) return
                        throw IllegalStateException("LAN_PEER_OFFLINE")
                    }
                    digest.update(chunk)
                    withContext(stateDispatcher) {
                        if (active === current) {
                            current.transferred += count
                            publishProgress(current)
                        }
                    }
                }
            }
            current.input = null
            if (active !== current) return
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val completed = sendMutex.withLock {
                active === current && peer.sendControl(LanControlFrame.FileComplete(current.item.id, hash))
            }
            if (!completed && active === current) throw IllegalStateException("LAN_PEER_OFFLINE")
        } catch (error: Exception) {
            withContext(stateDispatcher) {
                if (active === current) failOutgoing(current.item.id, errorCode(error))
            }
        } finally {
            current.input = null
            withContext(NonCancellable + stateDispatcher) {
                if (sendGeneration == generation) sendJob = null
            }
        }
    }

    private fun receiveBinary(bytes: ByteArray) {
        val current = active as? ActiveTransfer.Incoming ?: run {
            scope.launch { cancelActive("LAN_PROTOCOL_ERROR", notifyPeer = false, cancelled = false) }
            return
        }
        if (current.transferred + bytes.size > current.item.size) {
            scope.launch { cancelActive("LAN_PROTOCOL_ERROR", notifyPeer = true, cancelled = false) }
            return
        }
        runCatching { current.destination.write(bytes) }.onSuccess {
            current.transferred += bytes.size
            publishProgress(current)
        }.onFailure {
            scope.launch { cancelActive("DESTINATION_WRITE_FAILED", notifyPeer = true, cancelled = false) }
        }
    }

    private fun completeIncoming(fileId: String, sha256: String) {
        val current = active as? ActiveTransfer.Incoming ?: return
        if (current.item.id != fileId || current.transferred != current.item.size || !current.destination.verify(sha256)) {
            scope.launch { cancelActive("FILE_HASH_MISMATCH", notifyPeer = true, cancelled = false) }
            return
        }
        val completed = itemWithProgress(current, LanTransferStatus.Completed)
        active = null
        sendGeneration += 1
        replaceItem(completed)
        update { it.copy(currentFileId = null) }
        peer.sendControl(LanControlFrame.FileVerified(fileId))
        finishPeerFile(fileId)
        runCatching { foreground.stop() }
        startNext()
    }

    private fun completeOutgoing(fileId: String) {
        val current = active as? ActiveTransfer.Outgoing ?: return
        if (current.item.id != fileId) return
        active = null
        sendGeneration += 1
        replaceItem(itemWithProgress(current, LanTransferStatus.Completed))
        update { it.copy(currentFileId = null) }
        finishPeerFile(fileId)
        runCatching { foreground.stop() }
        startNext()
    }

    private suspend fun failOutgoing(fileId: String, code: String) {
        val current = active as? ActiveTransfer.Outgoing ?: return
        if (current.item.id != fileId) return
        active = null
        sendGeneration += 1
        current.input?.runCatching { close() }
        if (sendJob !== currentCoroutineContext()[Job]) sendJob?.cancel()
        sendMutex.withLock { sendJob = null }
        replaceItem(current.item.copy(status = LanTransferStatus.Failed, transferredBytes = current.transferred, error = code))
        update { it.copy(currentFileId = null, error = code) }
        finishPeerFile(fileId)
        runCatching { foreground.stop() }
        startNext()
    }

    private suspend fun cancelActive(code: String, notifyPeer: Boolean, cancelled: Boolean) {
        val current = active ?: return
        active = null
        (current as? ActiveTransfer.Outgoing)?.input?.runCatching { close() }
        sendJob?.cancel()
        sendGeneration += 1
        sendMutex.withLock {
            sendJob = null
            if (notifyPeer) peer.sendControl(if (cancelled) LanControlFrame.TransferCancel(current.item.id) else LanControlFrame.FileFailed(current.item.id, code))
        }
        if (current is ActiveTransfer.Incoming) current.destination.cancel()
        replaceItem(current.item.copy(
            status = if (cancelled) LanTransferStatus.Cancelled else LanTransferStatus.Failed,
            transferredBytes = current.transferred,
            error = code,
        ))
        update { it.copy(currentFileId = null, error = if (cancelled) it.error else code) }
        finishPeerFile(current.item.id)
        runCatching { foreground.stop() }
        startNext()
    }

    private fun publishProgress(current: ActiveTransfer) {
        val updated = itemWithProgress(current, LanTransferStatus.Transferring)
        replaceItem(updated)
        runCatching { foreground.update(updated.direction, updated.name, (updated.progress * 100).toInt()) }
    }

    private fun itemWithProgress(current: ActiveTransfer, status: LanTransferStatus): LanTransferItem {
        val elapsedMillis = (now() - current.startedAt).coerceAtLeast(1)
        return current.item.copy(
            status = status,
            transferredBytes = if (status == LanTransferStatus.Completed) current.item.size else current.transferred,
            speedBytesPerSecond = current.transferred * 1_000 / elapsedMillis,
            error = null,
        )
    }

    private fun finishPeerFile(fileId: String) = runCatching { peer.finishFile(fileId) }.getOrNull()
    private fun startForeground(item: LanTransferItem): Boolean = runCatching {
        foreground.start(item.direction, item.name, 0, ::cancelActive)
    }.onFailure { setError("FOREGROUND_SERVICE_UNAVAILABLE") }.isSuccess
    private fun setError(code: String) = update { it.copy(error = code) }
    private fun replaceItem(item: LanTransferItem) = update { state -> state.copy(items = state.items.map { if (it.id == item.id) item else it }) }
    private fun update(transform: (LanTransferUiState) -> LanTransferUiState) { mutableUiState.value = transform(mutableUiState.value) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sendGeneration += 1
        sendJob?.cancel()
        (active as? ActiveTransfer.Outgoing)?.input?.runCatching { close() }
        (active as? ActiveTransfer.Incoming)?.destination?.cancel()
        active = null
        runCatching { foreground.stop() }
        peer.close()
        scope.cancel()
    }

    private sealed class ActiveTransfer(open val item: LanTransferItem, open val startedAt: Long) {
        var transferred: Long = 0
        class Outgoing(override val item: LanTransferItem, override val startedAt: Long) : ActiveTransfer(item, startedAt) {
            @Volatile var input: InputStream? = null
        }
        class Incoming(
            override val item: LanTransferItem,
            override val startedAt: Long,
            val destination: LanTransferDestination,
        ) : ActiveTransfer(item, startedAt)
    }
}

private fun errorCode(error: Throwable): String = error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]+")) }
    ?: "LAN_TRANSFER_FAILED"
