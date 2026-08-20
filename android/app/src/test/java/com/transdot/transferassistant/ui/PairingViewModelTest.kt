package com.transdot.transferassistant.ui

import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.PairingCredential
import com.transdot.transferassistant.data.PairingFailure
import com.transdot.transferassistant.data.PairingRepository
import com.transdot.transferassistant.data.RebindRepository
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
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
    fun replacementRequiresExplicitConfirmation() = runTest(dispatcher.scheduler) {
        val repository = FakePairingRepository()
        val viewModel = PairingViewModel(repository, FakeSessionStore())
        viewModel.openManual()
        viewModel.updateManualCode("538219")

        viewModel.submitManualCode()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.replacementRequired)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(listOf(false), repository.replaceValues)

        viewModel.confirmReplacement()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(false, true), repository.replaceValues)
        assertEquals(PairingScreen.Success, viewModel.uiState.value.screen)
    }

    @Test
    fun rebindQrMustMatchCurrentServerOriginAndInstance() {
        val differentOrigin = rebindPayload(serverAddress = "https://other.example.com")
        val differentInstance = rebindPayload(instanceId = "instance-2")
        val store = FakeSessionStore(instanceId = "instance-1")
        val viewModel = PairingViewModel(FakePairingRepository(), store, rebindRepository = FakeRebindRepository())

        viewModel.onQRCodeScanned(differentOrigin)
        assertTrue(viewModel.uiState.value.rebindPayload == null)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("另一台服务器") == true)

        viewModel.onQRCodeScanned(differentInstance)
        assertTrue(viewModel.uiState.value.rebindPayload == null)
        assertTrue(viewModel.uiState.value.errorMessage?.contains("另一台服务器") == true)
    }

    @Test
    fun confirmedRebindReplacesActiveProfile() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(instanceId = "instance-1")
        val viewModel = PairingViewModel(FakePairingRepository(), store, rebindRepository = FakeRebindRepository())

        viewModel.onQRCodeScanned(rebindPayload())
        viewModel.confirmRebind()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, store.replacedSessions.size)
        assertEquals(0, store.savedSessions.size)
        assertEquals(PairingScreen.Success, viewModel.uiState.value.screen)
    }

    @Test
    fun legacyProfileWithoutInstanceCanRebindOnlyFromSameOrigin() = runTest(dispatcher.scheduler) {
        val store = FakeSessionStore(instanceId = "")
        val viewModel = PairingViewModel(FakePairingRepository(), store, rebindRepository = FakeRebindRepository())

        viewModel.onQRCodeScanned(rebindPayload())
        assertEquals("instance-1", viewModel.uiState.value.rebindPayload?.instanceId)

        viewModel.confirmRebind()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, store.replacedSessions.size)
    }

    private class FakePairingRepository : PairingRepository {
        val replaceValues = mutableListOf<Boolean>()

        override suspend fun approve(
            session: StoredSession,
            credential: PairingCredential,
            replaceExisting: Boolean,
        ) {
            replaceValues += replaceExisting
            if (!replaceExisting) throw PairingFailure.ReplacementRequired()
        }

        override suspend fun reject(session: StoredSession, credential: PairingCredential) = Unit
    }

    private class FakeSessionStore(private val instanceId: String = "") : SessionStore {
        val savedSessions = mutableListOf<ClaimedSession>()
        val replacedSessions = mutableListOf<ClaimedSession>()

        override fun load() = StoredSession(
            serverAddress = "https://transfer.example.com",
            deviceId = "master-1",
            masterToken = "master-token",
            profileId = "profile-1",
            profileName = "My server",
            instanceId = instanceId,
        )

        override fun prepare() = Unit

        override fun save(session: ClaimedSession) { savedSessions += session }

        override fun replaceActive(session: ClaimedSession) { replacedSessions += session }
    }

    private class FakeRebindRepository : RebindRepository {
        override suspend fun claim(payload: com.transdot.transferassistant.data.RebindPayload) = ClaimedSession(
            payload.serverAddress,
            "master-2",
            "new-token",
            payload.instanceId,
            payload.instanceFingerprint,
        )
    }

    private fun rebindPayload(
        serverAddress: String = "https://transfer.example.com",
        instanceId: String = "instance-1",
    ) = """{"v":2,"kind":"rebind","server_url":"$serverAddress","instance_id":"$instanceId","instance_fingerprint":"7f3a-91c2","rebind_session_id":"123e4567-e89b-12d3-a456-426614174000","rebind_secret":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","expires_at":"2099-08-20T12:00:00Z"}"""
}
