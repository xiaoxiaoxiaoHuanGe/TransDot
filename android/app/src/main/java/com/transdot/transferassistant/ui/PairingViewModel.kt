package com.transdot.transferassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.transdot.transferassistant.data.PairingCredential
import com.transdot.transferassistant.data.BootstrapPayload
import com.transdot.transferassistant.data.BootstrapRepository
import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.PairingFailure
import com.transdot.transferassistant.data.PairingPayload
import com.transdot.transferassistant.data.PairingRepository
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.StoredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingScreen {
    Home,
    Scanner,
    Manual,
    Success,
}

data class PairingUiState(
    val screen: PairingScreen = PairingScreen.Home,
    val serverAddress: String = "",
    val deviceId: String = "",
    val manualCode: String = "",
    val isSubmitting: Boolean = false,
    val replacementRequired: Boolean = false,
    val errorMessage: String? = null,
    val bootstrapPayload: BootstrapPayload? = null,
)

class PairingViewModel(
    private val repository: PairingRepository,
    private val sessionStore: SessionStore,
    private val bootstrapRepository: BootstrapRepository? = null,
    private val allowCleartext: Boolean = false,
    private val onBootstrapSaved: () -> Unit = {},
) : ViewModel() {
    private val storedSession: StoredSession? = sessionStore.load()
    private var pendingCredential: PairingCredential? = null
    private var pendingBootstrapSession: ClaimedSession? = null
    private val mutableUiState = MutableStateFlow(
        PairingUiState(
            serverAddress = storedSession?.serverAddress.orEmpty(),
            deviceId = storedSession?.deviceId.orEmpty(),
            errorMessage = if (storedSession == null) "Android Master 凭据不可用。" else null,
        ),
    )
    val uiState: StateFlow<PairingUiState> = mutableUiState.asStateFlow()

    fun openScanner() {
        mutableUiState.update {
            it.copy(screen = PairingScreen.Scanner, manualCode = "", errorMessage = null)
        }
    }

    fun openManual() {
        mutableUiState.update {
            it.copy(screen = PairingScreen.Manual, manualCode = "", errorMessage = null)
        }
    }

    fun returnHome() {
        if (mutableUiState.value.isSubmitting) return
        pendingCredential = null
        mutableUiState.update {
            it.copy(
                screen = PairingScreen.Home,
                manualCode = "",
                replacementRequired = false,
                errorMessage = null,
            )
        }
    }

    fun updateManualCode(value: String) {
        val digits = value.filter(Char::isDigit).take(PAIRING_CODE_LENGTH)
        mutableUiState.update { it.copy(manualCode = digits, errorMessage = null) }
    }

    fun submitManualCode() {
        val code = mutableUiState.value.manualCode
        if (code.length != PAIRING_CODE_LENGTH) {
            mutableUiState.update { it.copy(errorMessage = "请输入电脑上显示的 6 位配对码。") }
            return
        }
        submit(PairingCredential.Code(code), replaceExisting = false)
    }

    fun onQRCodeScanned(rawValue: String) {
        if (mutableUiState.value.isSubmitting || pendingCredential != null) return
        if (bootstrapRepository != null) {
            runCatching { BootstrapPayload.parse(rawValue, allowCleartext) }.getOrNull()?.let { payload ->
                mutableUiState.update { it.copy(bootstrapPayload = payload, errorMessage = null) }
                return
            }
        }
        val credential = runCatching { PairingPayload.parse(rawValue) }.getOrElse { error ->
            mutableUiState.update {
                it.copy(errorMessage = error.message ?: "这不是有效的传输助手二维码。")
            }
            return
        }
        submit(credential, replaceExisting = false)
    }

    fun cancelBootstrap() { pendingBootstrapSession = null; mutableUiState.update { it.copy(bootstrapPayload = null) } }

    fun confirmBootstrap() {
        val payload = mutableUiState.value.bootstrapPayload ?: return
        val bootstrap = bootstrapRepository ?: return
        mutableUiState.update { it.copy(isSubmitting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val claimed = pendingBootstrapSession ?: run { sessionStore.prepare(); bootstrap.claim(payload).also { pendingBootstrapSession = it } }
                sessionStore.save(claimed); pendingBootstrapSession = null; claimed
            }.onSuccess {
                mutableUiState.update { state -> state.copy(isSubmitting = false, bootstrapPayload = null, screen = PairingScreen.Success) }
                onBootstrapSaved()
            }.onFailure { error -> mutableUiState.update { it.copy(isSubmitting = false, errorMessage = error.message ?: "扫码绑定失败。") } }
        }
    }

    fun reportScannerError(message: String) {
        mutableUiState.update { it.copy(errorMessage = message) }
    }

    fun confirmReplacement() {
        val credential = pendingCredential ?: return
        mutableUiState.update { it.copy(replacementRequired = false) }
        submit(credential, replaceExisting = true)
    }

    fun cancelReplacement() {
        val session = storedSession
        val credential = pendingCredential
        pendingCredential = null
        mutableUiState.update {
            it.copy(
                screen = PairingScreen.Home,
                replacementRequired = false,
                isSubmitting = false,
                errorMessage = null,
            )
        }
        if (session != null && credential != null) {
            viewModelScope.launch { runCatching { repository.reject(session, credential) } }
        }
    }

    private fun submit(credential: PairingCredential, replaceExisting: Boolean) {
        val session = storedSession
        if (session == null) {
            mutableUiState.update { it.copy(errorMessage = "Android Master 凭据不可用。") }
            return
        }
        if (mutableUiState.value.isSubmitting) return

        pendingCredential = credential
        mutableUiState.update {
            it.copy(isSubmitting = true, replacementRequired = false, errorMessage = null)
        }
        viewModelScope.launch {
            runCatching { repository.approve(session, credential, replaceExisting) }
                .onSuccess {
                    if (credential is PairingCredential.QR && session.instanceId.isBlank() && credential.instanceId.isNotBlank()) {
                        session.profileId.takeIf(String::isNotBlank)?.let { sessionStore.updateInstance(it, credential.instanceId) }
                    }
                    pendingCredential = null
                    mutableUiState.update {
                        it.copy(
                            screen = PairingScreen.Success,
                            manualCode = "",
                            isSubmitting = false,
                            replacementRequired = false,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is PairingFailure.ReplacementRequired) {
                        mutableUiState.update {
                            it.copy(isSubmitting = false, replacementRequired = true, errorMessage = null)
                        }
                    } else {
                        pendingCredential = null
                        mutableUiState.update {
                            it.copy(
                                isSubmitting = false,
                                replacementRequired = false,
                                errorMessage = error.message ?: "配对失败，请稍后重试。",
                            )
                        }
                    }
                }
        }
    }

    class Factory(
        private val repository: PairingRepository,
        private val sessionStore: SessionStore,
        private val bootstrapRepository: BootstrapRepository? = null,
        private val allowCleartext: Boolean = false,
        private val onBootstrapSaved: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PairingViewModel::class.java))
            return PairingViewModel(repository, sessionStore, bootstrapRepository, allowCleartext, onBootstrapSaved) as T
        }
    }

    private companion object {
        const val PAIRING_CODE_LENGTH = 6
    }
}
