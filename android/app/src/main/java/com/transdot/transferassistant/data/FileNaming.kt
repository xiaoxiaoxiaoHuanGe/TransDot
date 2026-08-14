package com.transdot.transferassistant.data

fun allocateDocumentName(requestedName: String, existingNames: Set<String>): String {
    val safeName = requestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
    if (safeName !in existingNames) return safeName
    val lastDot = safeName.lastIndexOf('.')
    val hasExtension = lastDot > 0
    val stem = if (hasExtension) safeName.substring(0, lastDot) else safeName
    val extension = if (hasExtension) safeName.substring(lastDot) else ""
    var suffix = 1
    while ("$stem ($suffix)$extension" in existingNames) suffix += 1
    return "$stem ($suffix)$extension"
}
