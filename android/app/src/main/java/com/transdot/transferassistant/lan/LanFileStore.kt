package com.transdot.transferassistant.lan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.transdot.transferassistant.data.AppPreferences
import com.transdot.transferassistant.data.allocateDocumentName
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class LanFileMetadata(val sourceId: String, val name: String, val mime: String, val size: Long)

class LanFileStoreException(val code: String, cause: Throwable? = null) : Exception(code, cause)

interface LanContentAccess {
    val availableBytes: Long?
    fun inspect(sourceId: String): LanFileMetadata?
    fun openInput(sourceId: String): InputStream
    fun createDestination(name: String, mime: String): String
    fun openOutput(destinationId: String): OutputStream
    fun delete(destinationId: String)
}

class AndroidLanContentAccess(
    private val resolver: ContentResolver,
    private val preferences: AppPreferences,
) : LanContentAccess {
    override val availableBytes: Long? = null

    override fun inspect(sourceId: String): LanFileMetadata? {
        val uri = Uri.parse(sourceId)
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        val knownSize = size ?: resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        } ?: return null
        return LanFileMetadata(sourceId, name.orEmpty().ifBlank { "file" }, resolver.getType(uri).orEmpty(), knownSize)
    }

    override fun openInput(sourceId: String): InputStream =
        requireNotNull(resolver.openInputStream(Uri.parse(sourceId)))

    override fun createDestination(name: String, mime: String): String {
        val tree = preferences.load().defaultSaveTreeUri?.let(Uri::parse)
            ?: throw LanFileStoreException("DESTINATION_UNAVAILABLE")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val existing = mutableSetOf<String>()
        resolver.query(children, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) if (column >= 0) cursor.getString(column)?.let(existing::add)
        }
        return requireNotNull(DocumentsContract.createDocument(resolver, parent, mime, allocateDocumentName(name, existing))).toString()
    }

    override fun openOutput(destinationId: String): OutputStream =
        requireNotNull(resolver.openOutputStream(Uri.parse(destinationId), "w"))

    override fun delete(destinationId: String) {
        DocumentsContract.deleteDocument(resolver, Uri.parse(destinationId))
    }
}

data class LanSource(val metadata: LanFileMetadata, val input: InputStream)

class LanFileStore(private val access: LanContentAccess) {
    fun inspect(sourceId: String): LanFileMetadata {
        val metadata = access.inspect(sourceId) ?: throw LanFileStoreException("FILE_SIZE_UNKNOWN")
        if (metadata.size < 0) throw LanFileStoreException("FILE_SIZE_UNKNOWN")
        if (metadata.size > MAX_LAN_FILE_BYTES) throw LanFileStoreException("FILE_TOO_LARGE")
        return metadata.copy(name = sanitizeLanFilename(metadata.name))
    }

    fun openSource(sourceId: String): LanSource = try {
        LanSource(inspect(sourceId), access.openInput(sourceId))
    } catch (error: LanFileStoreException) {
        throw error
    } catch (error: Exception) {
        throw LanFileStoreException("SOURCE_UNAVAILABLE", error)
    }

    fun openDestination(name: String, mime: String, size: Long): LanDestination {
        if (size < 0) throw LanFileStoreException("FILE_SIZE_UNKNOWN")
        if (size > MAX_LAN_FILE_BYTES) throw LanFileStoreException("FILE_TOO_LARGE")
        if (access.availableBytes?.let { it < size } == true) throw LanFileStoreException("DESTINATION_SPACE_LOW")
        val destinationId = try {
            access.createDestination(sanitizeLanFilename(name), mime.ifBlank { "application/octet-stream" })
        } catch (error: Exception) {
            throw LanFileStoreException("DESTINATION_UNAVAILABLE", error)
        }
        return try {
            LanDestination(access, destinationId, size, access.openOutput(destinationId))
        } catch (error: Exception) {
            runCatching { access.delete(destinationId) }
            throw LanFileStoreException("DESTINATION_UNAVAILABLE", error)
        }
    }
}

class LanDestination internal constructor(
    private val access: LanContentAccess,
    private val destinationId: String,
    private val expectedSize: Long,
    private val output: OutputStream,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var bytesWritten = 0L
    private var terminal = false

    fun write(bytes: ByteArray, progress: (Long) -> Unit) {
        if (terminal || bytesWritten + bytes.size > expectedSize) fail("LAN_PROTOCOL_ERROR")
        try {
            output.write(bytes)
            digest.update(bytes)
            bytesWritten += bytes.size
            progress(bytesWritten)
        } catch (error: Exception) {
            fail("DESTINATION_WRITE_FAILED", error)
        }
    }

    fun finish(): String {
        if (bytesWritten != expectedSize) fail("LAN_PROTOCOL_ERROR")
        try {
            output.close()
        } catch (error: Exception) {
            fail("DESTINATION_WRITE_FAILED", error)
        }
        terminal = true
        return digest.digest().toHex()
    }

    fun verify(expectedSha256: String) {
        val actual = finish()
        if (actual != expectedSha256) {
            runCatching { access.delete(destinationId) }
            throw LanFileStoreException("FILE_HASH_MISMATCH")
        }
    }

    fun cancel() {
        if (terminal) return
        terminal = true
        runCatching { output.close() }
        runCatching { access.delete(destinationId) }
    }

    private fun fail(code: String, cause: Throwable? = null): Nothing {
        terminal = true
        runCatching { output.close() }
        runCatching { access.delete(destinationId) }
        throw LanFileStoreException(code, cause)
    }
}
