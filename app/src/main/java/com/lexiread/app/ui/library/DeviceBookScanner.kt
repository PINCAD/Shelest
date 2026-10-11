package com.lexiread.app.ui.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.lexiread.app.data.model.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class DeviceBookFile(
    val uri: Uri,
    val name: String,
    val folder: String,
    val size: Long
)

/** Scans only the folder the reader grants through Android's system folder picker. */
internal suspend fun findBooksInTree(
    resolver: ContentResolver,
    treeUri: Uri
): List<DeviceBookFile> = withContext(Dispatchers.IO) {
    val result = ArrayList<DeviceBookFile>()
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val folders = ArrayDeque<Pair<String, String>>()
    val seen = HashSet<String>()
    folders.add(rootId to "")
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )

    while (folders.isNotEmpty()) {
        coroutineContext.ensureActive()
        val (folderId, folderPath) = folders.removeFirst()
        if (!seen.add(folderId)) continue
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderId)
        // A cloud-backed DocumentsProvider can deny individual subfolders. Keep the books
        // found in accessible siblings instead of aborting the entire scan.
        try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: continue
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders.add(id to if (folderPath.isEmpty()) name else "$folderPath/$name")
                    } else if (isSupportedBookName(name)) {
                        result.add(
                            DeviceBookFile(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name = name,
                                folder = folderPath,
                                size = if (cursor.isNull(sizeColumn)) 0L else cursor.getLong(sizeColumn)
                            )
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (folderId == rootId) throw error
        }
    }
    result.sortedWith(compareBy({ it.name.lowercase() }, { it.folder.lowercase() }))
}

private fun isSupportedBookName(name: String): Boolean {
    val lower = name.lowercase()
    if (lower.endsWith(".fb2.zip")) return true
    val extension = lower.substringAfterLast('.', "")
    return extension != "zip" && FileType.fromExtension(extension) != null
}
