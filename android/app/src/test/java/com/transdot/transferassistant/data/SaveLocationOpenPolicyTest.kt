package com.transdot.transferassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveLocationOpenPolicyTest {
    @Test
    fun systemFolderUsesDocumentTreePicker() {
        val spec = systemSaveLocationIntentSpec()

        assertEquals("android.intent.action.OPEN_DOCUMENT_TREE", spec.action)
        assertNull(spec.mimeType)
        assertNull(spec.packageName)
    }

    @Test
    fun cxFolderUsesExplicitDirectoryViewIntent() {
        val spec = cxSaveLocationIntentSpec()

        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("resource/folder", spec.mimeType)
        assertEquals("com.cxinventor.file.explorer", spec.packageName)
    }

    @Test
    fun cxChoiceIsShownOnlyWhenAvailable() {
        assertEquals(
            listOf(SaveLocationChoice.SYSTEM),
            availableSaveLocationChoices(cxAvailable = false),
        )
        assertEquals(
            listOf(SaveLocationChoice.SYSTEM, SaveLocationChoice.CX),
            availableSaveLocationChoices(cxAvailable = true),
        )
    }
}
