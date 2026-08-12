package com.transdot.transferassistant.ui

import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.PairingCredential
import com.transdot.transferassistant.data.PairingFailure
import com.transdot.transferassistant.data.PairingRepository
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

    private class FakeSessionStore : SessionStore {
        override fun load() = StoredSession(
            serverAddress = "https://transfer.example.com",
            deviceId = "master-1",
            masterToken = "master-token",
        )

        override fun prepare() = Unit

        override fun save(session: ClaimedSession) = Unit
    }
}
