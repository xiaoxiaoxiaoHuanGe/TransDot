package com.transdot.transferassistant.ui

import android.net.Uri
import com.transdot.transferassistant.data.ClaimedSession
import com.transdot.transferassistant.data.MessageContext
import com.transdot.transferassistant.data.MessagePage
import com.transdot.transferassistant.data.RealtimeConnection
import com.transdot.transferassistant.data.SessionStore
import com.transdot.transferassistant.data.StoredSession
import com.transdot.transferassistant.data.TimelineEvent
import com.transdot.transferassistant.data.TimelineMessage
import com.transdot.transferassistant.data.TimelineRealtimeListener
import com.transdot.transferassistant.data.TimelineRepository
import com.transdot.transferassistant.data.UploadProgress
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
class TimelineViewModelTest {
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
    fun realtimeSendDeleteSearchAndLocateReconcileState() = runTest(dispatcher.scheduler) {
        val initial = message("message-1", "from server", "android_master")
        val repository = FakeTimelineRepository(initial)
        val viewModel = TimelineViewModel(repository, FakeSessionStore())

        viewModel.start()
        dispatcher.scheduler.advanceUntilIdle()
        repository.openRealtime()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("message-1"), viewModel.uiState.value.messages.map(TimelineMessage::id))
        assertEquals(TimelineConnectionState.Connected, viewModel.uiState.value.connectionState)

        val realtime = message("message-2", "from windows", "windows_browser")
        repository.emit(TimelineEvent.Created(realtime))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("message-1", "message-2"), viewModel.uiState.value.messages.map(TimelineMessage::id))

        viewModel.updateDraft("new android text")
        viewModel.sendText()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("new android text", repository.sentText)
        assertEquals("", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.messages.any { it.id == "message-sent" })

        viewModel.openSearch()
        viewModel.updateSearchQuery("windows")
        viewModel.search()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("message-2"), viewModel.uiState.value.searchResults.map(TimelineMessage::id))
        viewModel.locate("message-2")
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.searchOpen)
        assertEquals("message-2", viewModel.uiState.value.highlightedMessageId)

        val sent = viewModel.uiState.value.messages.first { it.id == "message-sent" }
        viewModel.requestDelete(listOf(realtime, sent))
        viewModel.confirmDelete()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("message-2", "message-sent"), repository.deletedIDs)
        assertFalse(viewModel.uiState.value.messages.any { it.id == "message-2" })
        assertFalse(viewModel.uiState.value.messages.any { it.id == "message-sent" })

        viewModel.stop()
        assertTrue(repository.connectionClosed)
    }

    @Test
    fun retryUploadContainsOnlyFilesThatDidNotComplete() = runTest(dispatcher.scheduler) {
        val repository = RetryTimelineRepository()
        val viewModel = TimelineViewModel(repository, FakeSessionStore())
        val files = listOf(Uri.EMPTY, Uri.EMPTY)

        viewModel.uploadFiles(files)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.retryUploadUris.size)
        viewModel.retryUpload()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(2, 1), repository.uploadBatchSizes)
    }

    private class FakeTimelineRepository(initial: TimelineMessage) : TimelineRepository {
        private var current = listOf(initial)
        private var listener: TimelineRealtimeListener? = null
        var sentText: String? = null
        val deletedIDs = mutableListOf<String>()
        var connectionClosed = false

        override suspend fun list(session: StoredSession, before: String?) = MessagePage(current, null)

        override suspend fun sendText(session: StoredSession, text: String): TimelineMessage {
            sentText = text
            return message("message-sent", text, "android_master").also { current = current + it }
        }

        override suspend fun delete(session: StoredSession, messageId: String) {
            deletedIDs += messageId
            current = current.filterNot { it.id == messageId }
        }

        override suspend fun search(session: StoredSession, query: String) =
            current.filter { it.textContent?.contains(query) == true }

        override suspend fun context(session: StoredSession, messageId: String) =
            MessageContext(messageId, current)

        override fun connect(session: StoredSession, listener: TimelineRealtimeListener): RealtimeConnection {
            this.listener = listener
            return object : RealtimeConnection {
                override fun close() {
                    connectionClosed = true
                }
            }
        }

        fun openRealtime() = listener?.onOpen() ?: Unit
        fun emit(event: TimelineEvent) {
            when (event) {
                is TimelineEvent.Created -> current = (current + event.message).distinctBy(TimelineMessage::id)
                is TimelineEvent.Deleted -> current = current.filterNot { it.id == event.messageId }
                else -> Unit
            }
            listener?.onEvent(event)
        }
    }

    private class RetryTimelineRepository : TimelineRepository {
        val uploadBatchSizes = mutableListOf<Int>()

        override suspend fun list(session: StoredSession, before: String?) = MessagePage(emptyList(), null)
        override suspend fun sendText(session: StoredSession, text: String) = error("unused")
        override suspend fun delete(session: StoredSession, messageId: String) = Unit
        override suspend fun search(session: StoredSession, query: String) = emptyList<TimelineMessage>()
        override suspend fun context(session: StoredSession, messageId: String) = MessageContext(messageId, emptyList())
        override fun connect(session: StoredSession, listener: TimelineRealtimeListener) = object : RealtimeConnection { override fun close() = Unit }

        override suspend fun upload(session: StoredSession, uris: List<Uri>, progress: (UploadProgress) -> Unit): List<TimelineMessage> {
            uploadBatchSizes += uris.size
            progress(UploadProgress("upload-1", "first", 1, 1, "complete", sourceUri = uris.first(), sourceIndex = 0))
            if (uploadBatchSizes.size == 1) error("second file failed")
            return emptyList()
        }
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

    private companion object {
        fun message(id: String, text: String, sourceType: String) = TimelineMessage(
            id = id,
            type = "text",
            batchId = null,
            sourceDeviceId = if (sourceType == "android_master") "master-1" else "browser-1",
            sourceDeviceType = sourceType,
            textContent = text,
            createdAt = "2026-08-12T10:00:${id.takeLast(1).filter(Char::isDigit).ifEmpty { "0" }}.000000000Z",
            metadataExpiresAt = null,
        )
    }
}
