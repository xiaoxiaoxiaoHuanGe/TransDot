package com.transdot.transferassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.BootstrapPayload
import com.transdot.transferassistant.data.BootstrapRepository
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.RebindPayload
import com.transdot.transferassistant.data.RebindRepository
import com.transdot.transferassistant.data.SetupFailure
import com.transdot.transferassistant.data.SetupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val serverAddress: String = "",
    val setupToken: String = "",
    val isSubmitting: Boolean = false,
    val isReady: Boolean = false,
    val needsSecureStorageRetry: Boolean = false,
    val deviceId: String = "",
    val errorMessage: String? = null,
    val bootstrapPayload: BootstrapPayload? = null,
    val rebindPayload: RebindPayload? = null,
)

class SetupViewModel(
    private val repository: SetupRepository,
    private val sessionStore: SessionStore,
    private val bootstrapRepository: BootstrapRepository? = null,
    private val rebindRepository: RebindRepository? = null,
    private val allowCleartext: Boolean = false,
) : ViewModel() {
    private var pendingSession: ClaimedSession? = null
    private var pendingBootstrapSession: ClaimedSession? = null
    private var pendingRebindSession: ClaimedSession? = null
    private val storedSession = sessionStore.load()
    private val mutableUiState = MutableStateFlow(
        SetupUiState(
            serverAddress = storedSession?.serverAddress.orEmpty(),
            isReady = storedSession != null,
            deviceId = storedSession?.deviceId.orEmpty(),
        ),
    )
    val uiState: StateFlow<SetupUiState> = mutableUiState.asStateFlow()

    fun updateServerAddress(value: String) {
        mutableUiState.update { it.copy(serverAddress = value, errorMessage = null) }
    }

    fun updateSetupToken(value: String) {
        mutableUiState.update { it.copy(setupToken = value, errorMessage = null) }
    }

    fun onBootstrapScanned(rawValue: String) {
        runCatching { BootstrapPayload.parse(rawValue, allowCleartext) }.getOrNull()?.let { payload ->
            mutableUiState.update { it.copy(bootstrapPayload = payload, rebindPayload = null, serverAddress = payload.serverAddress, errorMessage = null) }
            return
        }
        runCatching { RebindPayload.parse(rawValue, allowCleartext) }.getOrNull()?.let { payload ->
            mutableUiState.update { it.copy(bootstrapPayload = null, rebindPayload = payload, serverAddress = payload.serverAddress, errorMessage = null) }
            return
        }
        mutableUiState.update { it.copy(errorMessage = "这不是有效的服务器绑定或重绑定二维码。") }
    }

    fun cancelBootstrap() {
        pendingBootstrapSession = null
        mutableUiState.update {
            it.copy(
                bootstrapPayload = null,
                serverAddress = pendingServerAddress() ?: it.serverAddress,
                needsSecureStorageRetry = hasPendingSession(),
            )
        }
    }

    fun cancelRebind() {
        pendingRebindSession = null
        mutableUiState.update {
            it.copy(
                rebindPayload = null,
                serverAddress = pendingServerAddress() ?: it.serverAddress,
                needsSecureStorageRetry = hasPendingSession(),
            )
        }
    }

    fun confirmRebind() {
        val payload = mutableUiState.value.rebindPayload ?: return
        val repository = rebindRepository ?: return
        mutableUiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = pendingRebindSession ?: run { sessionStore.prepare(); repository.claim(payload).also { pendingRebindSession = it } }
                sessionStore.save(session); clearPendingSessions(); session
            }.onSuccess { session -> mutableUiState.update { it.copy(isSubmitting = false, isReady = true, rebindPayload = null, needsSecureStorageRetry = false, deviceId = session.deviceId) } }
                .onFailure { error -> mutableUiState.update { it.copy(isSubmitting = false, needsSecureStorageRetry = hasPendingSession(), errorMessage = error.message ?: "手机重绑定失败。") } }
        }
    }

    fun confirmBootstrap() {
        val payload = mutableUiState.value.bootstrapPayload ?: return
        val repository = bootstrapRepository ?: return
        mutableUiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = pendingBootstrapSession ?: run {
                    sessionStore.prepare()
                    repository.claim(payload).also { pendingBootstrapSession = it }
                }
                sessionStore.save(session)
                clearPendingSessions()
                session
            }.onSuccess { session -> mutableUiState.update { it.copy(isSubmitting = false, isReady = true, bootstrapPayload = null, needsSecureStorageRetry = false, deviceId = session.deviceId) } }
                .onFailure { error -> mutableUiState.update { it.copy(isSubmitting = false, needsSecureStorageRetry = hasPendingSession(), errorMessage = error.message ?: "扫码绑定失败。") } }
        }
    }

    fun claimServer() {
        val snapshot = mutableUiState.value
        if (snapshot.isSubmitting) return
        if (snapshot.serverAddress.isBlank() || (pendingSession == null && snapshot.setupToken.isBlank())) {
            mutableUiState.update { it.copy(errorMessage = "请填写服务器地址和初始化密钥。") }
            return
        }

        mutableUiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val session = pendingSession ?: run {
                    try {
                        sessionStore.prepare()
                    } catch (failure: Exception) {
                        throw SetupFailure.SecureStorage(failure)
                    }
                    if (repository.isInitialized(snapshot.serverAddress)) {
                        throw SetupFailure.AlreadyInitialized()
                    }
                    repository.claim(snapshot.serverAddress, snapshot.setupToken).also {
                        pendingSession = it
                    }
                }
                try {
                    sessionStore.save(session)
                } catch (failure: Exception) {
                    throw SetupFailure.SecureStorage(failure)
                }
                clearPendingSessions()
                session
            }.onSuccess { session ->
                mutableUiState.update {
                    it.copy(
                        serverAddress = session.serverAddress,
                        setupToken = "",
                        isSubmitting = false,
                        isReady = true,
                        needsSecureStorageRetry = false,
                        deviceId = session.deviceId,
                    )
                }
            }.onFailure { error ->
                mutableUiState.update {
                    val pending = pendingSession
                    it.copy(
                        serverAddress = pending?.serverAddress ?: it.serverAddress,
                        setupToken = if (pending != null) "" else it.setupToken,
                        isSubmitting = false,
                        needsSecureStorageRetry = hasPendingSession(),
                        errorMessage = error.message ?: "初始化失败，请稍后重试。",
                    )
                }
            }
        }
    }

    private fun hasPendingSession(): Boolean =
        pendingSession != null || pendingBootstrapSession != null || pendingRebindSession != null

    private fun pendingServerAddress(): String? =
        pendingRebindSession?.serverAddress ?: pendingBootstrapSession?.serverAddress ?: pendingSession?.serverAddress

    private fun clearPendingSessions() {
        pendingSession = null
        pendingBootstrapSession = null
        pendingRebindSession = null
    }

    class Factory(
        private val repository: SetupRepository,
        private val sessionStore: SessionStore,
        private val bootstrapRepository: BootstrapRepository? = null,
        private val rebindRepository: RebindRepository? = null,
        private val allowCleartext: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SetupViewModel::class.java))
            return SetupViewModel(repository, sessionStore, bootstrapRepository, rebindRepository, allowCleartext) as T
        }
    }
}
