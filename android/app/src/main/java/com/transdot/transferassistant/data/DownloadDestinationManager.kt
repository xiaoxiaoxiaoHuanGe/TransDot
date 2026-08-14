package com.transdot.transferassistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

class DefaultSaveFolderUnavailable(cause: Throwable? = null) : Exception("默认保存位置已不可用，请重新选择。", cause)

class DownloadDestinationManager(
    context: Context,
    private val preferences: AppPreferences,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val packageManager = appContext.packageManager

    fun persistDefaultTree(uri: Uri) {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        preferences.setDefaultSaveTree(uri)
    }

    fun clearDefaultTree() = preferences.setDefaultSaveTree(null)

    fun createInDefaultFolder(filename: String, mimeType: String): Uri {
        val tree = preferences.load().defaultSaveTreeUri?.let(Uri::parse)
            ?: throw DefaultSaveFolderUnavailable()
        return runCatching {
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val existing = mutableSetOf<String>()
            resolver.query(childUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) if (nameColumn >= 0) cursor.getString(nameColumn)?.let(existing::add)
            }
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                mimeType.ifBlank { "application/octet-stream" },
                allocateDocumentName(filename, existing),
            ) ?: throw DefaultSaveFolderUnavailable()
        }.getOrElse { failure ->
            clearDefaultTree()
            throw DefaultSaveFolderUnavailable(failure)
        }
    }

    fun folderLabel(): String? = preferences.load().defaultSaveTreeUri?.let(Uri::parse)?.let { uri ->
        runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':') }.getOrNull()
            ?.takeIf(String::isNotBlank) ?: "已选择目录"
    }

    fun openFileIntent(uri: Uri, mimeType: String) = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun systemFolderIntent(initialUri: Uri? = null): Intent? =
        (preferences.load().defaultSaveTreeUri?.let(Uri::parse) ?: initialUri)?.let { uri ->
            Intent(systemSaveLocationIntentSpec().action).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }

    fun cxFolderIntent(): Intent? {
        val tree = preferences.load().defaultSaveTreeUri?.let(Uri::parse) ?: return null
        val directory = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return null
        val spec = cxSaveLocationIntentSpec()
        return Intent(spec.action).apply {
            setDataAndType(directory, spec.mimeType)
            setPackage(spec.packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.takeIf { it.resolveActivity(packageManager) != null }
    }

}
