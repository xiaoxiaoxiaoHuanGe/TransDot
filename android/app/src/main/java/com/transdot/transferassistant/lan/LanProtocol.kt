package com.transdot.transferassistant.lan

import java.security.MessageDigest
import org.json.JSONObject

const val LAN_CHUNK_BYTES = 64 * 1024
const val MAX_LAN_FILES = 20
const val MAX_LAN_FILE_BYTES = 2L * 1024 * 1024 * 1024

class LanProtocolException(val code: String) : IllegalArgumentException(code)

sealed interface LanControlFrame {
    val fileId: String?

    data class FileOffer(
        override val fileId: String,
        val name: String,
        val mime: String,
        val size: Long,
    ) : LanControlFrame
    data class FileAccept(override val fileId: String) : LanControlFrame
    data class FileReject(override val fileId: String, val code: String) : LanControlFrame
    data class FileComplete(override val fileId: String, val sha256: String) : LanControlFrame
    data class FileVerified(override val fileId: String) : LanControlFrame
    data class FileFailed(override val fileId: String, val code: String) : LanControlFrame
    data object QueueComplete : LanControlFrame { override val fileId: String? = null }
    data class TransferCancel(override val fileId: String) : LanControlFrame
}

object LanProtocol {
    fun encode(frame: LanControlFrame): String = JSONObject().apply {
        when (frame) {
            is LanControlFrame.FileOffer -> {
                put("type", "file_offer"); put("file_id", frame.fileId); put("name", frame.name)
                put("mime", frame.mime); put("size", frame.size)
            }
            is LanControlFrame.FileAccept -> { put("type", "file_accept"); put("file_id", frame.fileId) }
            is LanControlFrame.FileReject -> { put("type", "file_reject"); put("file_id", frame.fileId); put("code", frame.code) }
            is LanControlFrame.FileComplete -> { put("type", "file_complete"); put("file_id", frame.fileId); put("sha256", frame.sha256) }
            is LanControlFrame.FileVerified -> { put("type", "file_verified"); put("file_id", frame.fileId) }
            is LanControlFrame.FileFailed -> { put("type", "file_failed"); put("file_id", frame.fileId); put("code", frame.code) }
            LanControlFrame.QueueComplete -> put("type", "queue_complete")
            is LanControlFrame.TransferCancel -> { put("type", "transfer_cancel"); put("file_id", frame.fileId) }
        }
    }.toString()

    fun parse(json: String): LanControlFrame = try {
        val value = JSONObject(json)
        when (value.requireString("type")) {
            "file_offer" -> {
                value.requireKeys("type", "file_id", "name", "mime", "size")
                val size = value.getLong("size")
                if (size < 0) protocolError()
                if (size > MAX_LAN_FILE_BYTES) throw LanProtocolException("FILE_TOO_LARGE")
                LanControlFrame.FileOffer(value.requireString("file_id"), value.requireString("name"), value.requireString("mime"), size)
            }
            "file_accept" -> value.fileOnly("file_accept") { LanControlFrame.FileAccept(it) }
            "file_reject" -> value.fileCode("file_reject") { id, code -> LanControlFrame.FileReject(id, code) }
            "file_complete" -> {
                value.requireKeys("type", "file_id", "sha256")
                val hash = value.requireString("sha256")
                if (!hash.matches(Regex("[0-9a-f]{64}"))) protocolError()
                LanControlFrame.FileComplete(value.requireString("file_id"), hash)
            }
            "file_verified" -> value.fileOnly("file_verified") { LanControlFrame.FileVerified(it) }
            "file_failed" -> value.fileCode("file_failed") { id, code -> LanControlFrame.FileFailed(id, code) }
            "queue_complete" -> { value.requireKeys("type"); LanControlFrame.QueueComplete }
            "transfer_cancel" -> value.fileOnly("transfer_cancel") { LanControlFrame.TransferCancel(it) }
            else -> protocolError()
        }
    } catch (error: LanProtocolException) {
        throw error
    } catch (_: Exception) {
        protocolError()
    }

    private inline fun JSONObject.fileOnly(type: String, create: (String) -> LanControlFrame): LanControlFrame {
        requireKeys("type", "file_id")
        if (requireString("type") != type) protocolError()
        return create(requireString("file_id"))
    }

    private inline fun JSONObject.fileCode(type: String, create: (String, String) -> LanControlFrame): LanControlFrame {
        requireKeys("type", "file_id", "code")
        if (requireString("type") != type) protocolError()
        return create(requireString("file_id"), requireString("code"))
    }
}

class LanTransferQueue {
    private val pending = ArrayDeque<LanControlFrame.FileOffer>()
    private var active: LanControlFrame.FileOffer? = null

    fun enqueue(files: List<LanControlFrame.FileOffer>) {
        if (pending.size + (if (active == null) 0 else 1) + files.size > MAX_LAN_FILES) {
            throw LanProtocolException("TOO_MANY_FILES")
        }
        files.forEach { if (it.size > MAX_LAN_FILE_BYTES) throw LanProtocolException("FILE_TOO_LARGE") }
        pending.addAll(files)
    }

    fun startNext(): LanControlFrame.FileOffer? = active ?: pending.removeFirstOrNull()?.also { active = it }

    fun complete(fileId: String) = finish(fileId)
    fun cancel(fileId: String) = finish(fileId)

    private fun finish(fileId: String) {
        if (active?.fileId != fileId) protocolError()
        active = null
    }
}

fun sanitizeLanFilename(requested: String): String {
    val withoutTraversal = requested.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }.joinToString("_")
    return withoutTraversal.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "download" }
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun JSONObject.requireString(key: String): String = getString(key).takeIf(String::isNotBlank) ?: protocolError()

private fun JSONObject.requireKeys(vararg expected: String) {
    if (keys().asSequence().toSet() != expected.toSet()) protocolError()
}

private fun protocolError(): Nothing = throw LanProtocolException("LAN_PROTOCOL_ERROR")
