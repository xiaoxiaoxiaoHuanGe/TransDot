package com.transdot.transferassistant.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.transdot.transferassistant.data.MessageContext
import com.transdot.transferassistant.data.RealtimeConnection
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.StoredSession
import com.transdot.transferassistant.data.TimelineEvent
import com.transdot.transferassistant.data.TimelineFailure
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.data.TimelineRealtimeListener
import com.transdot.transferassistant.data.TimelineRepository
import com.transdot.transferassistant.data.UploadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TimelineConnectionState {
    Connecting,
    Connected,
    Offline,
}

data class TimelineUiState(
    val messages: List<TimelineMessage> = emptyList(),
    val nextBefore: String? = null,
    val draft: String = "",
    val isInitialLoading: Boolean = true,
    val isLoadingOlder: Boolean = false,
    val isSending: Boolean = false,
    val connectionState: TimelineConnectionState = TimelineConnectionState.Connecting,
    val errorMessage: String? = null,
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<TimelineMessage> = emptyList(),
    val isSearching: Boolean = false,
    val highlightedMessageId: String? = null,
    val deleteTarget: TimelineMessage? = null,
    val isDeleting: Boolean = false,
    val credentialInvalid: Boolean = false,
    val uploads: List<UploadProgress> = emptyList(),
    val isUploading: Boolean = false,
    val retryUploadUris: List<Uri> = emptyList(),
    val downloadMessageId: String? = null,
    val downloadProgress: Float? = null,
)

class TimelineViewModel(
    private val repository: TimelineRepository,
    sessionStore: SessionStore,
) : ViewModel() {
    private val session: StoredSession? = sessionStore.load()
    private val mutableUiState = MutableStateFlow(
        TimelineUiState(
            errorMessage = if (session == null) "Android Master 凭据不可用。" else null,
            credentialInvalid = session == null,
        ),
    )
    val uiState: StateFlow<TimelineUiState> = mutableUiState.asStateFlow()

    private var started = false
    private var connection: RealtimeConnection? = null
    private var connectionGeneration = 0
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var refreshJob: Job? = null
    private var synchronizationInProgress = false
    private val bufferedEvents = mutableListOf<TimelineEvent>()

    fun start() {
        if (started || session == null) return
        started = true
        refreshLatest()
        connectRealtime()
    }

    fun stop() {
        if (!started) return
        started = false
        connectionGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
        connection?.close()
        connection = null
        mutableUiState.update { it.copy(connectionState = TimelineConnectionState.Offline) }
    }

    fun updateDraft(value: String) {
        mutableUiState.update { it.copy(draft = value, errorMessage = null) }
    }

    fun sendText() {
        val activeSession = session ?: return
        val content = mutableUiState.value.draft
        if (content.isBlank() || mutableUiState.value.isSending || content.toByteArray().size > MAX_TEXT_BYTES) return
        mutableUiState.update { it.copy(isSending = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { repository.sendText(activeSession, content) }
                .onSuccess { created ->
                    mutableUiState.update {
                        it.copy(
                            messages = mergeMessages(it.messages, listOf(created)),
                            draft = "",
                            isSending = false,
                        )
                    }
                }
                .onFailure { failure -> handleFailure(failure) { it.copy(isSending = false) } }
        }
    }

    fun uploadFiles(uris: List<Uri>) {
        val activeSession = session ?: return
        if (uris.isEmpty() || mutableUiState.value.isUploading) return
        mutableUiState.update {
            it.copy(isUploading = true, uploads = emptyList(), retryUploadUris = uris, errorMessage = null)
        }
        viewModelScope.launch {
            runCatching {
                repository.upload(activeSession, uris) { progress ->
                    mutableUiState.update { state ->
                        state.copy(uploads = (state.uploads.filterNot { it.uploadId == progress.uploadId } + progress))
                    }
                }
            }.onSuccess { created ->
                mutableUiState.update {
                    it.copy(
                        messages = mergeMessages(it.messages, created),
                        isUploading = false,
                        retryUploadUris = emptyList(),
                    )
                }
                delay(700)
                mutableUiState.update { it.copy(uploads = emptyList()) }
            }.onFailure { failure ->
                handleFailure(failure) { it.copy(isUploading = false) }
            }
        }
    }

    fun retryUpload() {
        val uris = mutableUiState.value.retryUploadUris
        if (uris.isNotEmpty()) uploadFiles(uris)
    }

    fun download(message: TimelineMessage, destination: Uri) {
        val activeSession = session ?: return
        if (mutableUiState.value.downloadMessageId != null) return
        mutableUiState.update { it.copy(downloadMessageId = message.id, downloadProgress = 0f, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                repository.download(activeSession, message, destination) { copied, total ->
                    mutableUiState.update { state ->
                        state.copy(downloadProgress = if (total > 0) copied.toFloat() / total else null)
                    }
                }
            }.onSuccess {
                mutableUiState.update { it.copy(downloadMessageId = null, downloadProgress = null) }
            }.onFailure { failure ->
                handleFailure(failure) { it.copy(downloadMessageId = null, downloadProgress = null) }
            }
        }
    }

    suspend fun loadImage(message: TimelineMessage, original: Boolean): Bitmap? {
        val activeSession = session ?: return null
        return runCatching { repository.loadImage(activeSession, message, original) }.getOrNull()
    }

    fun loadOlder() {
        val activeSession = session ?: return
        val before = mutableUiState.value.nextBefore ?: return
        if (mutableUiState.value.isLoadingOlder) return
        mutableUiState.update { it.copy(isLoadingOlder = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { repository.list(activeSession, before) }
                .onSuccess { page ->
                    mutableUiState.update {
                        it.copy(
                            messages = mergeMessages(page.messages, it.messages),
                            nextBefore = page.nextBefore,
                            isLoadingOlder = false,
                        )
                    }
                }
                .onFailure { failure -> handleFailure(failure) { it.copy(isLoadingOlder = false) } }
        }
    }

    fun requestDelete(message: TimelineMessage) {
        mutableUiState.update { it.copy(deleteTarget = message, errorMessage = null) }
    }

    fun cancelDelete() {
        if (mutableUiState.value.isDeleting) return
        mutableUiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val activeSession = session ?: return
        val target = mutableUiState.value.deleteTarget ?: return
        if (mutableUiState.value.isDeleting) return
        mutableUiState.update { it.copy(isDeleting = true) }
        viewModelScope.launch {
            runCatching { repository.delete(activeSession, target.id) }
                .onSuccess {
                    mutableUiState.update {
                        it.copy(
                            messages = it.messages.filterNot { message -> message.id == target.id },
                            searchResults = it.searchResults.filterNot { message -> message.id == target.id },
                            deleteTarget = null,
                            isDeleting = false,
                        )
                    }
                }
                .onFailure { failure -> handleFailure(failure) { it.copy(isDeleting = false) } }
        }
    }

    fun openSearch() {
        mutableUiState.update { it.copy(searchOpen = true, errorMessage = null) }
    }

    fun closeSearch() {
        mutableUiState.update { it.copy(searchOpen = false) }
    }

    fun updateSearchQuery(value: String) {
        mutableUiState.update { it.copy(searchQuery = value, errorMessage = null) }
    }

    fun search() {
        val activeSession = session ?: return
        val query = mutableUiState.value.searchQuery.trim()
        if (query.isEmpty() || mutableUiState.value.isSearching) return
        mutableUiState.update { it.copy(isSearching = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching { repository.search(activeSession, query) }
                .onSuccess { results ->
                    mutableUiState.update { it.copy(searchResults = results, isSearching = false) }
                }
                .onFailure { failure -> handleFailure(failure) { it.copy(isSearching = false) } }
        }
    }

    fun locate(messageId: String) {
        val activeSession = session ?: return
        viewModelScope.launch {
            runCatching { repository.context(activeSession, messageId) }
                .onSuccess(::showContext)
                .onFailure(::handleFailure)
        }
    }

    fun clearHighlight() {
        mutableUiState.update { it.copy(highlightedMessageId = null) }
    }

    fun clearError() {
        mutableUiState.update { it.copy(errorMessage = null, credentialInvalid = false) }
    }

    private fun showContext(context: MessageContext) {
        mutableUiState.update {
            it.copy(
                messages = context.messages,
                nextBefore = null,
                highlightedMessageId = context.targetMessageId,
                searchOpen = false,
            )
        }
    }

    private fun refreshLatest() {
        val activeSession = session ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            synchronizationInProgress = true
            bufferedEvents.clear()
            runCatching { repository.list(activeSession) }
                .onSuccess { page ->
                    mutableUiState.update {
                        it.copy(
                            messages = page.messages,
                            nextBefore = page.nextBefore,
                            isInitialLoading = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { failure ->
                    handleFailure(failure) { it.copy(isInitialLoading = false) }
                }
            synchronizationInProgress = false
            bufferedEvents.toList().also { bufferedEvents.clear() }.forEach(::applyRealtimeEvent)
        }
    }

    private fun connectRealtime() {
        val activeSession = session ?: return
        if (!started) return
        connection?.close()
        val generation = ++connectionGeneration
        mutableUiState.update { it.copy(connectionState = TimelineConnectionState.Connecting) }
        connection = repository.connect(activeSession, object : TimelineRealtimeListener {
            override fun onOpen() = dispatch(generation) {
                reconnectAttempt = 0
                mutableUiState.update { it.copy(connectionState = TimelineConnectionState.Connected) }
                refreshLatest()
            }

            override fun onEvent(event: TimelineEvent) = dispatch(generation) {
                if (synchronizationInProgress) bufferedEvents += event else applyRealtimeEvent(event)
            }

            override fun onClosed() = dispatch(generation) {
                mutableUiState.update { it.copy(connectionState = TimelineConnectionState.Offline) }
                scheduleReconnect()
            }

            override fun onFailure(failure: TimelineFailure) = dispatch(generation) {
                if (failure is TimelineFailure.Unauthorized) {
                    markCredentialInvalid(failure.message.orEmpty())
                } else {
                    mutableUiState.update { it.copy(connectionState = TimelineConnectionState.Offline) }
                    scheduleReconnect()
                }
            }
        })
    }

    private fun dispatch(generation: Int, action: () -> Unit) {
        viewModelScope.launch {
            if (started && generation == connectionGeneration) action()
        }
    }

    private fun scheduleReconnect() {
        if (!started || reconnectJob?.isActive == true) return
        val delayMillis = (800L * (1L shl reconnectAttempt.coerceAtMost(4))).coerceAtMost(10_000L)
        reconnectAttempt += 1
        reconnectJob = viewModelScope.launch {
            delay(delayMillis)
            if (started) connectRealtime()
        }
    }

    private fun applyRealtimeEvent(event: TimelineEvent) {
        when (event) {
            is TimelineEvent.Created -> mutableUiState.update {
                it.copy(messages = mergeMessages(it.messages, listOf(event.message)))
            }
            is TimelineEvent.Deleted -> mutableUiState.update {
                it.copy(
                    messages = it.messages.filterNot { message -> message.id == event.messageId },
                    searchResults = it.searchResults.filterNot { message -> message.id == event.messageId },
                )
            }
            is TimelineEvent.FileExpired -> mutableUiState.update { state ->
                state.copy(messages = state.messages.map { message ->
                    if (message.id == event.messageId && message.file != null) {
                        message.copy(file = message.file.copy(status = "expired", expiredReason = "ttl"))
                    } else message
                })
            }
            TimelineEvent.DeviceReplaced -> markCredentialInvalid("此设备已被替换。")
            TimelineEvent.Unknown -> Unit
        }
    }

    private fun handleFailure(failure: Throwable, update: (TimelineUiState) -> TimelineUiState = { it }) {
        if (failure is TimelineFailure.Unauthorized) {
            markCredentialInvalid(failure.message.orEmpty())
            return
        }
        mutableUiState.update {
            update(it).copy(errorMessage = failure.message ?: "请求失败，请稍后重试。")
        }
    }

    private fun markCredentialInvalid(message: String) {
        started = false
        connectionGeneration += 1
        reconnectJob?.cancel()
        connection?.close()
        connection = null
        mutableUiState.update {
            it.copy(
                credentialInvalid = true,
                connectionState = TimelineConnectionState.Offline,
                errorMessage = message,
                isSending = false,
                isLoadingOlder = false,
                isSearching = false,
                isDeleting = false,
                isUploading = false,
                downloadMessageId = null,
                downloadProgress = null,
            )
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    class Factory(
        private val repository: TimelineRepository,
        private val sessionStore: SessionStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TimelineViewModel::class.java))
            return TimelineViewModel(repository, sessionStore) as T
        }
    }

    private companion object {
        const val MAX_TEXT_BYTES = 100 * 1024

        fun mergeMessages(current: List<TimelineMessage>, incoming: List<TimelineMessage>): List<TimelineMessage> =
            (current + incoming)
                .associateBy(TimelineMessage::id)
                .values
                .sortedWith(compareBy(TimelineMessage::createdAt, TimelineMessage::id))
    }
}
