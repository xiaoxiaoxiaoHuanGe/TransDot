package com.transdot.transferassistant.data

internal const val CX_FILE_MANAGER_PACKAGE = "com.cxinventor.file.explorer"

internal data class SaveLocationIntentSpec(
    val action: String,
    val mimeType: String? = null,
    val packageName: String? = null,
)

internal fun systemSaveLocationIntentSpec() =
    SaveLocationIntentSpec(action = "android.intent.action.OPEN_DOCUMENT_TREE")

internal fun cxSaveLocationIntentSpec() = SaveLocationIntentSpec(
    action = "android.intent.action.VIEW",
    mimeType = "resource/folder",
    packageName = CX_FILE_MANAGER_PACKAGE,
)

internal enum class SaveLocationChoice { SYSTEM, CX }

internal fun availableSaveLocationChoices(cxAvailable: Boolean) = buildList {
    add(SaveLocationChoice.SYSTEM)
    if (cxAvailable) add(SaveLocationChoice.CX)
}
