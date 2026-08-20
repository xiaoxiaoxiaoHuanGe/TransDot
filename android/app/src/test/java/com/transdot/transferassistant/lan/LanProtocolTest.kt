package com.transdot.transferassistant.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanProtocolTest {
    @Test
    fun constantsMatchWebProtocol() {
        assertEquals(64 * 1024, LAN_CHUNK_BYTES)
        assertEquals(20, MAX_LAN_FILES)
        assertEquals(2L * 1024 * 1024 * 1024, MAX_LAN_FILE_BYTES)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(byteArrayOf()),
        )
    }

    @Test
    fun roundTripsEveryControlFrameAndPreservesChineseNames() {
        val frames = listOf<LanControlFrame>(
            LanControlFrame.FileOffer("f1", "报告.txt", "text/plain", MAX_LAN_FILE_BYTES),
            LanControlFrame.FileAccept("f1"),
            LanControlFrame.FileReject("f1", "DESTINATION_UNAVAILABLE"),
            LanControlFrame.FileComplete("f1", "00".repeat(32)),
            LanControlFrame.FileVerified("f1"),
            LanControlFrame.FileFailed("f1", "FILE_HASH_MISMATCH"),
            LanControlFrame.QueueComplete,
            LanControlFrame.TransferCancel("f1"),
        )
        frames.forEach { frame -> assertEquals(frame, LanProtocol.parse(LanProtocol.encode(frame))) }
    }

    @Test
    fun rejectsOversizeUnknownFieldsAndUnknownTypes() {
        assertCode("FILE_TOO_LARGE") {
            LanProtocol.parse("""{"type":"file_offer","file_id":"f","name":"x","mime":"","size":${MAX_LAN_FILE_BYTES + 1}}""")
        }
        assertCode("LAN_PROTOCOL_ERROR") {
            LanProtocol.parse("""{"type":"file_accept","file_id":"f","extra":true}""")
        }
        assertCode("LAN_PROTOCOL_ERROR") { LanProtocol.parse("""{"type":"future"}""") }
    }

    @Test
    fun sanitizesTraversalWithoutDamagingUnicode() {
        assertEquals("a_b_.txt", sanitizeLanFilename("../a\\b?.txt"))
        assertEquals("报告.txt", sanitizeLanFilename("报告.txt"))
    }

    @Test
    fun queueAllowsTwentyFilesAndOnlyOneActiveFile() {
        val queue = LanTransferQueue()
        queue.enqueue((1..20).map { offer("f$it") })
        assertCode("TOO_MANY_FILES") { queue.enqueue(listOf(offer("overflow"))) }
        assertEquals("f1", queue.startNext()?.fileId)
        assertEquals("f1", queue.startNext()?.fileId)
        assertCode("LAN_PROTOCOL_ERROR") { queue.complete("wrong") }
        queue.complete("f1")
        assertEquals("f2", queue.startNext()?.fileId)
        queue.cancel("f2")
        assertEquals("f3", queue.startNext()?.fileId)
    }

    private fun offer(id: String) = LanControlFrame.FileOffer(id, "$id.txt", "text/plain", 1)

    private fun assertCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: LanProtocolException) {
            assertEquals(code, error.code)
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }
}
