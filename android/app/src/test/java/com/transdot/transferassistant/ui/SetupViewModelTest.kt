package com.transdot.transferassistant.ui

import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.BootstrapPayload
import com.transdot.transferassistant.data.BootstrapRepository
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.RebindPayload
import com.transdot.transferassistant.data.RebindRepository
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

    @Test
    fun confirmsBootstrapBeforeClaimAndSavesSession() = runTest(dispatcher.scheduler) {
        val claimed = ClaimedSession("https://transfer.example.com", "device-2", "token-2", "instance-2", "7f3a-91c2")
        val sessionStore = FakeSessionStore(failSave = false)
        val bootstrap = object : BootstrapRepository {
            var calls = 0
            override suspend fun claim(payload: BootstrapPayload): ClaimedSession { calls++; return claimed }
        }
        val viewModel = SetupViewModel(FakeSetupRepository(claimed), sessionStore, bootstrap)

        viewModel.onBootstrapScanned("""{"v":2,"kind":"bootstrap","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","bootstrap_session_id":"123e4567-e89b-12d3-a456-426614174000","bootstrap_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""")
        assertFalse(viewModel.uiState.value.isReady)
        assertEquals("7f3a-91c2", viewModel.uiState.value.bootstrapPayload?.instanceFingerprint)

        viewModel.confirmBootstrap()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, bootstrap.calls)
        assertEquals(1, sessionStore.saveCalls)
        assertTrue(viewModel.uiState.value.isReady)
    }

    @Test
    fun retriesBootstrapSecureSaveWithoutClaimingAgain() = runTest(dispatcher.scheduler) {
        val claimed = ClaimedSession("https://transfer.example.com", "device-2", "token-2", "instance-2", "7f3a-91c2")
        val store = FakeSessionStore(failSave = true)
        val bootstrap = object : BootstrapRepository {
            var calls = 0
            override suspend fun claim(payload: BootstrapPayload): ClaimedSession { calls++; return claimed }
        }
        val viewModel = SetupViewModel(FakeSetupRepository(claimed), store, bootstrap)
        viewModel.onBootstrapScanned("""{"v":2,"kind":"bootstrap","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","bootstrap_session_id":"123e4567-e89b-12d3-a456-426614174000","bootstrap_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""")
        viewModel.confirmBootstrap(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, bootstrap.calls)
        assertTrue(viewModel.uiState.value.needsSecureStorageRetry)
        store.failSave = false
        viewModel.confirmBootstrap(); dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, bootstrap.calls)
        assertTrue(viewModel.uiState.value.isReady)
    }

    @Test
    fun confirmsRebindAndStoresReplacementSession() = runTest(dispatcher.scheduler) {
        val claimed = ClaimedSession("https://transfer.example.com", "device-new", "token-new", "instance-2", "7f3a-91c2")
        val store = FakeSessionStore(failSave = false)
        val rebind = object : RebindRepository {
            var calls = 0
            override suspend fun claim(payload: RebindPayload): ClaimedSession { calls++; return claimed }
        }
        val viewModel = SetupViewModel(FakeSetupRepository(claimed), store, rebindRepository = rebind)

        viewModel.onBootstrapScanned("""{"v":2,"kind":"rebind","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""")
        assertEquals("instance-2", viewModel.uiState.value.rebindPayload?.instanceId)
        viewModel.confirmRebind()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, rebind.calls)
        assertEquals(1, store.saveCalls)
        assertTrue(viewModel.uiState.value.isReady)
    }

    @Test
    fun rebindDoesNotReusePendingManualSetupSession() = runTest(dispatcher.scheduler) {
        val setupSession = ClaimedSession("https://transfer.example.com", "setup-device", "setup-token")
        val reboundSession = ClaimedSession("https://transfer.example.com", "rebound-device", "rebound-token", "instance-2", "7f3a-91c2")
        val store = FakeSessionStore(failSave = true)
        val rebind = object : RebindRepository {
            var calls = 0
            override suspend fun claim(payload: RebindPayload): ClaimedSession { calls++; return reboundSession }
        }
        val viewModel = SetupViewModel(FakeSetupRepository(setupSession), store, rebindRepository = rebind)
        viewModel.updateServerAddress(setupSession.serverAddress)
        viewModel.updateSetupToken("owner-token")
        viewModel.claimServer()
        dispatcher.scheduler.advanceUntilIdle()

        store.failSave = false
        viewModel.onBootstrapScanned("""{"v":2,"kind":"rebind","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""")
        viewModel.confirmRebind()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, rebind.calls)
        assertEquals("rebound-device", store.savedSessions.last().deviceId)
    }

    @Test
    fun failedRebindClaimPreservesPendingManualSaveState() = runTest(dispatcher.scheduler) {
        val setupSession = ClaimedSession("https://setup.example.com", "setup-device", "setup-token")
        val store = FakeSessionStore(failSave = true)
        val failingRebind = object : RebindRepository {
            override suspend fun claim(payload: RebindPayload): ClaimedSession = error("claim failed")
        }
        val viewModel = SetupViewModel(FakeSetupRepository(setupSession), store, rebindRepository = failingRebind)
        viewModel.updateServerAddress(setupSession.serverAddress)
        viewModel.updateSetupToken("owner-token")
        viewModel.claimServer()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onBootstrapScanned("""{"v":2,"kind":"rebind","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""")
        viewModel.confirmRebind()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.cancelRebind()

        assertTrue(viewModel.uiState.value.needsSecureStorageRetry)
        assertEquals(setupSession.serverAddress, viewModel.uiState.value.serverAddress)
    }

    @Test
    fun cancellingRebindAfterSaveFailureClearsItsRetryState() = runTest(dispatcher.scheduler) {
        val reboundSession = ClaimedSession("https://transfer.example.com", "rebound-device", "rebound-token", "instance-2", "7f3a-91c2")
        val store = FakeSessionStore(failSave = true)
        val rebind = object : RebindRepository {
            override suspend fun claim(payload: RebindPayload): ClaimedSession = reboundSession
        }
        val viewModel = SetupViewModel(FakeSetupRepository(reboundSession), store, rebindRepository = rebind)
        viewModel.onBootstrapScanned("""{"v":2,"kind":"rebind","server_url":"https://transfer.example.com","instance_id":"instance-2","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}""")
        viewModel.confirmRebind()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.needsSecureStorageRetry)

        viewModel.cancelRebind()

        assertFalse(viewModel.uiState.value.needsSecureStorageRetry)
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
        val savedSessions = mutableListOf<ClaimedSession>()

        override fun load(): StoredSession? = null

        override fun prepare() = Unit

        override fun save(session: ClaimedSession) {
            saveCalls++
            if (failSave) error("simulated secure storage failure")
            savedSessions += session
        }
    }
}
