package com.dopa.cleaner.source.saf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Size
import com.dopa.cleaner.core.BitmapLoader
import com.dopa.cleaner.core.DeleteResult
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.MediaSource
import com.dopa.cleaner.core.MediaTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class SafFolderSource(
    private val context: Context,
    override val id: String,
    override val displayName: String,
    private val treeUri: Uri,
) : MediaSource {
    override val canDelete: Boolean = true

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    override fun enumerate(): Flow<MediaRef> = flow {
        val hasPermission = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
        if (!hasPermission) throw SecurityException("Permissão da pasta \"$displayName\" expirou. Adicione novamente.")
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<Pair<String, String>>().apply { add(rootId to "/") }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (docId, path) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val rows = mutableListOf<MediaRef>()
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: childId
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) null else c.getLong(3)
                    val modified = if (c.isNull(4)) null else c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.add(childId to "$path$name/")
                        continue
                    }
                    val resolved = mime?.takeIf { MediaTypes.isSupported(it) } ?: MediaTypes.fromName(name) ?: continue
                    rows += MediaRef(
                        sourceId = id,
                        itemId = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId).toString(),
                        name = name,
                        mimeType = resolved,
                        sizeBytes = size,
                        location = "$displayName: $path",
                        version = modified?.let { "${size ?: -1}:$it" },
                    )
                }
            }
            for (r in rows) emit(r)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun loadFrames(ref: MediaRef, targetPx: Int): List<Bitmap> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(ref.itemId)
        val thumb = runCatching { context.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null) }.getOrNull()
        when {
            thumb != null && !ref.isVideo -> listOf(thumb)
            ref.isVideo -> {
                val frames = runCatching { BitmapLoader.videoFrames(targetPx) { it.setDataSource(context, uri) } }.getOrDefault(emptyList())
                frames.ifEmpty { listOfNotNull(thumb) }
            }
            else -> listOf(BitmapLoader.decodeUri(context, uri, targetPx))
        }
    }

    override suspend fun delete(ref: MediaRef): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(ref.itemId))) {
                DeleteResult.Deleted
            } else {
                DeleteResult.Failed("Provedor recusou exclusão")
            }
        } catch (e: UnsupportedOperationException) {
            DeleteResult.Unsupported("Provedor de \"$displayName\" não suporta exclusão")
        } catch (e: Exception) {
            DeleteResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
