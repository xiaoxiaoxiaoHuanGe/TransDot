package com.transdot.transferassistant.lan

import com.transdot.transferassistant.data.StoredSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanSignalingClientTest {
    @Test
    fun authenticatesSendsReadyAndAcceptsServerSession() = runTest {
        val transport = FakeSocketTransport()
        val client = LanSignalingClient(session(), transport, backgroundScope, reconnectDelayMillis = 100)
        val events = mutableListOf<LanSignalEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.toList(events) }

        client.start()
        assertEquals("wss://example.test/ws", transport.requests.single().url)
        assertEquals("Bearer token", transport.requests.single().authorization)
        transport.open()
        assertEquals("lan.ready", transport.sentTypes().single())
        assertFalse(client.sendAnswer("answer-before-session"))

        transport.message("""{"event_id":"e1","type":"lan.peer_online","timestamp":"2026-08-20T00:00:00Z","data":{"session_id":"server-session","data":{}}}""")
        assertTrue(events.last() is LanSignalEvent.PeerOnline)
        transport.message("""{"event_id":"e2","type":"lan.offer","timestamp":"2026-08-20T00:00:00Z","data":{"session_id":"server-session","data":{"sdp":"browser-offer"}}}""")
        assertEquals("browser-offer", (events.last() as LanSignalEvent.Offer).sdp)
        assertTrue(client.sendAnswer("android-answer"))
        assertEquals("lan.answer", transport.sentTypes().last())
    }

    @Test
    fun sendsOnlyHostIceAndNeverLeaksFileMetadata() = runTest {
        val transport = FakeSocketTransport()
        val client = LanSignalingClient(session(), transport, backgroundScope)
        client.start(); transport.open()
        transport.message("""{"event_id":"e1","type":"lan.peer_online","timestamp":"2026-08-20T00:00:00Z","data":{"session_id":"s","data":{}}}""")

        assertTrue(client.sendIce("candidate:1 1 UDP 1 phone.local 5000 typ host"))
        assertFalse(client.sendIce("candidate:2 1 UDP 1 203.0.113.2 5001 typ srflx"))
        client.markConnected()

        val captured = transport.sent.joinToString("\n")
        assertFalse(captured.contains("filename", ignoreCase = true))
        assertFalse(captured.contains("sha256", ignoreCase = true))
        assertFalse(captured.contains("file_offer", ignoreCase = true))
        assertEquals(listOf("lan.ready", "lan.ice", "lan.connected"), transport.sentTypes())
    }

    @Test
    fun reportsStructuredErrorsReconnectsWaitingAndLeavesOnClose() = runTest {
        val transport = FakeSocketTransport()
        val client = LanSignalingClient(session("http://192.168.1.5:3366"), transport, backgroundScope, reconnectDelayMillis = 100)
        val events = mutableListOf<LanSignalEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { client.events.toList(events) }
        client.start(); transport.open()
        transport.message("""{"event_id":"e1","type":"lan.peer_online","timestamp":"2026-08-20T00:00:00Z","data":{"session_id":"s","data":{}}}""")
        transport.message("""{"event_id":"e2","type":"lan.error","timestamp":"2026-08-20T00:00:00Z","data":{"code":"LAN_SESSION_BUSY"}}""")
        assertEquals("LAN_SESSION_BUSY", (events.last() as LanSignalEvent.Error).code)

        transport.closed()
        assertTrue(events.last() is LanSignalEvent.Waiting)
        advanceTimeBy(101)
        assertEquals(2, transport.requests.size)
        assertEquals("ws://192.168.1.5:3366/ws", transport.requests.last().url)
        transport.open()
        transport.message("""{"event_id":"e3","type":"lan.peer_online","timestamp":"2026-08-20T00:00:00Z","data":{"session_id":"new-s","data":{}}}""")
        assertTrue(client.restartSession())
        assertEquals(listOf("lan.ready", "lan.ready", "lan.leave", "lan.ready"), transport.sentTypes())
        transport.message("""{"event_id":"e4","type":"lan.peer_online","timestamp":"2026-08-20T00:00:00Z","data":{"session_id":"restart-s","data":{}}}""")
        client.close()
        assertEquals("lan.leave", transport.sentTypes().last())
        assertTrue(transport.sockets.last().closed)
    }

    private fun session(address: String = "https://example.test") = StoredSession(address, "android", "token")
}

private class FakeSocketTransport : LanWebSocketTransport {
    val requests = mutableListOf<LanSocketRequest>()
    val sockets = mutableListOf<FakeLanSocket>()
    val sent get() = sockets.flatMap { it.sent }
    private var listener: LanSocketListener? = null

    override fun connect(request: LanSocketRequest, listener: LanSocketListener): LanSocket {
        requests += request
        this.listener = listener
        return FakeLanSocket().also(sockets::add)
    }

    fun open() = listener!!.onOpen()
    fun message(value: String) = listener!!.onMessage(value)
    fun closed() = listener!!.onClosed()
    fun sentTypes() = sent.map { org.json.JSONObject(it).getString("type") }
}

private class FakeLanSocket : LanSocket {
    val sent = mutableListOf<String>()
    var closed = false
    override fun send(text: String): Boolean = sent.add(text)
    override fun close() { closed = true }
}
