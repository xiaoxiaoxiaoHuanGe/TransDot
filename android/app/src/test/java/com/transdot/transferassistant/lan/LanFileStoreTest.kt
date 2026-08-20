package com.transdot.transferassistant.lan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanFileStoreTest {
    @Test
    fun rejectsUnknownOversizeAndInsufficientSpace() {
        assertStoreCode("FILE_SIZE_UNKNOWN") { LanFileStore(FakeAccess(size = null)).inspect("source") }
        assertStoreCode("FILE_TOO_LARGE") { LanFileStore(FakeAccess(size = MAX_LAN_FILE_BYTES + 1)).inspect("source") }
        assertStoreCode("DESTINATION_SPACE_LOW") {
            LanFileStore(FakeAccess(size = 10, availableBytes = 9)).openDestination("file.bin", "", 10)
        }
    }

    @Test
    fun streamsSourceAndDestinationWithProgressAndHash() {
        val access = FakeAccess(size = 3, sourceBytes = "abc".encodeToByteArray())
        val store = LanFileStore(access)
        val source = store.openSource("source")
        assertEquals("报告.txt", source.metadata.name)
        assertEquals("616263", source.input.readBytes().toHex())

        val progress = mutableListOf<Long>()
        val destination = store.openDestination("报告.txt", "text/plain", 3)
        destination.write("abc".encodeToByteArray()) { progress += it }
        val hash = destination.finish()
        assertEquals(listOf(3L), progress)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertArrayEquals("abc".encodeToByteArray(), access.output.toByteArray())
    }

    @Test
    fun writeFailureAndHashMismatchDeletePartialOutput() {
        val failing = FakeAccess(size = 3, failWrite = true)
        val failedDestination = LanFileStore(failing).openDestination("x", "", 3)
        assertStoreCode("DESTINATION_WRITE_FAILED") { failedDestination.write(byteArrayOf(1)) {} }
        assertTrue(failing.deleted)

        val mismatch = FakeAccess(size = 3)
        val mismatchDestination = LanFileStore(mismatch).openDestination("x", "", 3)
        mismatchDestination.write("abc".encodeToByteArray()) {}
        assertStoreCode("FILE_HASH_MISMATCH") { mismatchDestination.verify("00".repeat(32)) }
        assertTrue(mismatch.deleted)
    }

    @Test
    fun cancelDeletesPartialAndEmptyFileCanFinish() {
        val cancelled = FakeAccess(size = 1)
        LanFileStore(cancelled).openDestination("x", "", 1).cancel()
        assertTrue(cancelled.deleted)

        val empty = FakeAccess(size = 0)
        val destination = LanFileStore(empty).openDestination("empty", "", 0)
        assertEquals(sha256Hex(byteArrayOf()), destination.finish())
        assertEquals(0, empty.output.size())
    }

    private fun assertStoreCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: LanFileStoreException) {
            assertEquals(code, error.code)
        }
    }

    private class FakeAccess(
        private val size: Long?,
        private val sourceBytes: ByteArray = byteArrayOf(),
        override val availableBytes: Long? = Long.MAX_VALUE,
        private val failWrite: Boolean = false,
    ) : LanContentAccess {
        val output = ByteArrayOutputStream()
        var deleted = false

        override fun inspect(sourceId: String) = size?.let { LanFileMetadata(sourceId, "报告.txt", "text/plain", it) }
        override fun openInput(sourceId: String): InputStream = ByteArrayInputStream(sourceBytes)
        override fun createDestination(name: String, mime: String): String = "destination"
        override fun openOutput(destinationId: String): OutputStream = if (failWrite) object : OutputStream() {
            override fun write(value: Int) = throw IOException("disk failure")
        } else output
        override fun delete(destinationId: String) { deleted = true }
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
