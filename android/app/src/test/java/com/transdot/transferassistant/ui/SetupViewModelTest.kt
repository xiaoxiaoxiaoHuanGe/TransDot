package com.transdot.transferassistant.ui

import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.SetupRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun retriesSecureSaveWithoutClaimingServerAgain() = runTest(dispatcher.scheduler) {
        val claimed = ClaimedSession(
            serverAddress = "https://transfer.example.com",
            deviceId = "device-1",
            masterToken = "master-token",
        )
        val repository = FakeSetupRepository(claimed)
        val sessionStore = FakeSessionStore(failSave = true)
        val viewModel = SetupViewModel(repository, sessionStore)

        viewModel.updateServerAddress(claimed.serverAddress)
        viewModel.updateSetupToken("owner-token")
        viewModel.claimServer()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.claimCalls)
        assertEquals(1, sessionStore.saveCalls)
        assertTrue(viewModel.uiState.value.needsSecureStorageRetry)
        assertFalse(viewModel.uiState.value.isReady)

        sessionStore.failSave = false
        viewModel.claimServer()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, repository.claimCalls)
        assertEquals(2, sessionStore.saveCalls)
        assertFalse(viewModel.uiState.value.needsSecureStorageRetry)
        assertTrue(viewModel.uiState.value.isReady)
        assertEquals("device-1", viewModel.uiState.value.deviceId)
    }

    private class FakeSetupRepository(
        private val claimedSession: ClaimedSession,
    ) : SetupRepository {
        var claimCalls = 0

        override suspend fun isInitialized(serverAddress: String) = false

        override suspend fun claim(serverAddress: String, setupToken: String): ClaimedSession {
            claimCalls++
            return claimedSession
        }
    }

    private class FakeSessionStore(
        var failSave: Boolean,
    ) : SessionStore {
        var saveCalls = 0

        override fun load(): StoredSession? = null

        override fun prepare() = Unit

        override fun save(session: ClaimedSession) {
            saveCalls++
            if (failSave) error("simulated secure storage failure")
        }
    }
}
