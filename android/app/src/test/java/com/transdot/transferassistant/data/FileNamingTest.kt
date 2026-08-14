package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamingTest {
    @Test
    fun allocatesSuffixBeforeExtension() {
        assertEquals("report (2).pdf", allocateDocumentName("report.pdf", setOf("report.pdf", "report (1).pdf")))
        assertEquals("archive (1)", allocateDocumentName("archive", setOf("archive")))
    }
}
