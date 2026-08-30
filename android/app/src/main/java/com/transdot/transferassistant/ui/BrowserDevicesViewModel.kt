package com.transdot.transferassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.transdot.transferassistant.data.BrowserDevice
import com.transdot.transferassistant.data.BrowserDeviceRepository
import com.transdot.transferassistant.data.BrowserDeviceFailure
import com.transdot.transferassistant.data.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserDevicesUiState(
    val devices: List<BrowserDevice> = emptyList(),
    val maximumCount: Int = 10,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val actionDeviceId: String? = null,
    val errorMessage: String? = null,
    val supported: Boolean = true,
)

class BrowserDevicesViewModel(
    private val repository: BrowserDeviceRepository,
    sessionStore: SessionStore,
) : ViewModel() {
    private val session = sessionStore.load()
    private val mutableUiState = MutableStateFlow(BrowserDevicesUiState())
    val uiState: StateFlow<BrowserDevicesUiState> = mutableUiState.asStateFlow()

    init { refresh(initial = true) }

    fun refresh(initial: Boolean = false) {
        val currentSession = session
        if (currentSession == null) {
            mutableUiState.update { it.copy(loading = false, refreshing = false, errorMessage = "Android Master 凭据不可用。") }
            return
        }
        mutableUiState.update { it.copy(loading = initial, refreshing = !initial, errorMessage = null) }
        viewModelScope.launch {
            runCatching { repository.list(currentSession) }
                .onSuccess { result -> mutableUiState.update { it.copy(devices = result.devices, maximumCount = result.maximumCount, loading = false, refreshing = false, supported = true) } }
                .onFailure { error ->
                    mutableUiState.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            supported = error !is BrowserDeviceFailure.Unsupported,
                            errorMessage = if (error is BrowserDeviceFailure.Unsupported) null else error.message ?: "无法加载浏览器设备。",
                        )
                    }
                }
        }
    }

    fun rename(deviceId: String, displayName: String) = perform(deviceId) { currentSession ->
        val updated = repository.rename(currentSession, deviceId, displayName)
        mutableUiState.update { state -> state.copy(devices = state.devices.map { if (it.id == deviceId) updated else it }) }
    }

    fun revoke(deviceId: String) = perform(deviceId) { currentSession ->
        repository.revoke(currentSession, deviceId)
        mutableUiState.update { state -> state.copy(devices = state.devices.filterNot { it.id == deviceId }) }
    }

    fun clearError() = mutableUiState.update { it.copy(errorMessage = null) }

    private fun perform(deviceId: String, action: suspend (com.transdot.transferassistant.data.StoredSession) -> Unit) {
        val currentSession = session ?: return
        if (mutableUiState.value.actionDeviceId != null) return
        mutableUiState.update { it.copy(actionDeviceId = deviceId, errorMessage = null) }
        viewModelScope.launch {
            runCatching { action(currentSession) }
                .onFailure { error -> mutableUiState.update { it.copy(errorMessage = error.message ?: "设备操作失败。") } }
            mutableUiState.update { it.copy(actionDeviceId = null) }
        }
    }

    class Factory(private val repository: BrowserDeviceRepository, private val sessionStore: SessionStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BrowserDevicesViewModel::class.java))
            return BrowserDevicesViewModel(repository, sessionStore) as T
        }
    }
}
