package com.transdot.transferassistant.ui

import com.transdot.transferassistant.data.BrowserDevice
import com.transdot.transferassistant.data.BrowserDeviceList
import com.transdot.transferassistant.data.BrowserDeviceRepository
import com.transdot.transferassistant.data.BrowserDeviceFailure
import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.StoredSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserDevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsRenamesAndRevokesAfterServerSuccess() = runTest(dispatcher.scheduler) {
        val repository = FakeBrowserDevicesRepository()
        val viewModel = BrowserDevicesViewModel(repository, FakeBrowserSessionStore())
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(listOf("browser-1", "browser-2"), viewModel.uiState.value.devices.map(BrowserDevice::id))

        viewModel.rename("browser-1", "Study")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Study", viewModel.uiState.value.devices.first().displayName)

        viewModel.revoke("browser-1")
        assertEquals("browser-1", viewModel.uiState.value.actionDeviceId)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("browser-2"), viewModel.uiState.value.devices.map(BrowserDevice::id))
        assertNull(viewModel.uiState.value.actionDeviceId)
    }

    @Test
    fun revokeFailureKeepsDeviceAndShowsError() = runTest(dispatcher.scheduler) {
        val repository = FakeBrowserDevicesRepository().apply { revokeFailure = IllegalStateException("offline") }
        val viewModel = BrowserDevicesViewModel(repository, FakeBrowserSessionStore())
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.revoke("browser-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.devices.size)
        assertEquals("offline", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun hidesManagementWhenConnectedServerDoesNotSupportIt() = runTest(dispatcher.scheduler) {
        val repository = FakeBrowserDevicesRepository().apply { listFailure = BrowserDeviceFailure.Unsupported() }
        val viewModel = BrowserDevicesViewModel(repository, FakeBrowserSessionStore())
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.supported)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    private class FakeBrowserDevicesRepository : BrowserDeviceRepository {
        private var current = listOf(
            BrowserDevice("browser-1", "Office", "2026-08-30T10:00:00Z", "2026-08-30T11:00:00Z"),
            BrowserDevice("browser-2", "Home", "2026-08-30T09:00:00Z", null),
        )
        var revokeFailure: Throwable? = null
        var listFailure: Throwable? = null
        override suspend fun list(session: StoredSession): BrowserDeviceList {
            listFailure?.let { throw it }
            return BrowserDeviceList(current, current.size, 10)
        }
        override suspend fun rename(session: StoredSession, deviceId: String, displayName: String): BrowserDevice =
            current.first { it.id == deviceId }.copy(displayName = displayName).also { updated -> current = current.map { if (it.id == deviceId) updated else it } }
        override suspend fun revoke(session: StoredSession, deviceId: String) {
            revokeFailure?.let { throw it }
            current = current.filterNot { it.id == deviceId }
        }
    }

    private class FakeBrowserSessionStore : SessionStore {
        override fun load() = StoredSession("https://transfer.example.com", "master-1", "master-token")
        override fun prepare() = Unit
        override fun save(session: ClaimedSession) = Unit
    }
}
