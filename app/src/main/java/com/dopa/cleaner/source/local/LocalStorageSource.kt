package com.dopa.cleaner.source.local

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Size
import com.dopa.cleaner.config.ScanOptions
import com.dopa.cleaner.core.BitmapLoader
import com.dopa.cleaner.core.DeleteResult
import com.dopa.cleaner.core.FrameRequest
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.MediaSource
import com.dopa.cleaner.core.MediaTypes
import com.dopa.cleaner.core.ViewTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

class LocalStorageSource(
    private val context: Context,
    private val options: ScanOptions = ScanOptions(),
) : MediaSource {
    override val id: String = ID
    override val displayName: String = "Armazenamento do aparelho"
    override val canDelete: Boolean = true

    fun roots(): List<Pair<String, File>> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { vol -> vol.directory?.let { vol.getDescription(context) to it } }
            .distinctBy { it.second.absolutePath }
    }

    private fun labelFor(path: String, roots: List<Pair<String, File>>): Pair<String, String> {
        val root = roots.firstOrNull { path.startsWith(it.second.absolutePath) }
        val parent = File(path).parent.orEmpty()
        return if (root == null) "Aparelho" to parent else root.first to parent.removePrefix(root.second.absolutePath).ifEmpty { "/" }
    }

    private fun skipPath(path: String): Boolean {
        if (options.isExcluded(path)) return true
        if (options.skipThumbnailDirs && path.contains("/.thumbnails/", ignoreCase = true)) return true
        if (options.skipHiddenDirs && path.split('/').dropLast(1).any { it.startsWith(".") }) return true
        return SKIP_DIRS.any { path.contains("/$it/") }
    }

    private val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.Files.FileColumns.SIZE} >= ?"

    private fun selectionArgs(): Array<String> = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        (options.minFileKb.toLong() * 1024).toString(),
    )

    override suspend fun estimateCount(): Int? = withContext(Dispatchers.IO) {
        if (options.deepScan) return@withContext null
        runCatching {
            MediaStore.getExternalVolumeNames(context).sumOf { vol ->
                val args = Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs())
                }
                context.contentResolver.query(
                    MediaStore.Files.getContentUri(vol),
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    args,
                    null,
                )?.use { it.count } ?: 0
            }
        }.getOrNull()
    }

    override fun enumerate(): Flow<MediaRef> = flow {
        if (!Environment.isExternalStorageManager()) {
            throw SecurityException("Permissão 'Acesso a todos os arquivos' não concedida")
        }
        if (options.deepScan) walkFileSystem(this) else queryMediaStore(this)
    }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION")
    private suspend fun queryMediaStore(out: FlowCollector<MediaRef>) {
        val roots = roots()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        for (vol in MediaStore.getExternalVolumeNames(context)) {
            val cursor = context.contentResolver.query(
                MediaStore.Files.getContentUri(vol),
                projection,
                selection,
                selectionArgs(),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            ) ?: continue
            cursor.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val iData = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iMod = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val iW = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val iH = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val path = c.getString(iData) ?: continue
                    if (skipPath(path)) continue
                    val name = c.getString(iName) ?: File(path).name
                    val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val mime = c.getString(iMime)?.takeIf { MediaTypes.isSupported(it) }
                        ?: MediaTypes.fromName(name)
                        ?: if (isVideo) "video/mp4" else "image/jpeg"
                    val size = c.getLong(iSize)
                    val id = c.getLong(iId)
                    val contentUri = ContentUris.withAppendedId(
                        if (isVideo) MediaStore.Video.Media.getContentUri(vol) else MediaStore.Images.Media.getContentUri(vol),
                        id,
                    )
                    val (label, folder) = labelFor(path, roots)
                    out.emit(
                        MediaRef(
                            sourceId = ID,
                            itemId = path,
                            name = name,
                            mimeType = mime,
                            sizeBytes = size,
                            location = "$label: $folder",
                            version = "$size:${c.getLong(iMod) * 1000}",
                            width = if (c.isNull(iW)) null else c.getInt(iW),
                            height = if (c.isNull(iH)) null else c.getInt(iH),
                            extra = contentUri.toString(),
                        ),
                    )
                }
            }
        }
    }

    private suspend fun walkFileSystem(out: FlowCollector<MediaRef>) {
        val minBytes = options.minFileKb.toLong() * 1024
        for ((label, root) in roots()) {
            val rootPath = root.absolutePath
            val stack = ArrayDeque<File>().apply { add(root) }
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (f in children) {
                    if (f.isDirectory) {
                        if (skipPath(f.absolutePath + "/x")) continue
                        if (runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)) continue
                        stack.add(f)
                        continue
                    }
                    val mime = MediaTypes.fromName(f.name) ?: continue
                    val len = f.length()
                    if (len < minBytes || options.isExcluded(f.absolutePath)) continue
                    out.emit(
                        MediaRef(
                            sourceId = ID,
                            itemId = f.absolutePath,
                            name = f.name,
                            mimeType = mime,
                            sizeBytes = len,
                            location = "$label: ${f.parent?.removePrefix(rootPath).orEmpty().ifEmpty { "/" }}",
                            version = "$len:${f.lastModified()}",
                        ),
                    )
                }
            }
        }
    }

    override suspend fun loadFrames(ref: MediaRef, request: FrameRequest): List<Bitmap> = withContext(Dispatchers.IO) {
        val file = File(ref.itemId)
        val contentUri = ref.extra?.let(Uri::parse)
        if (request.allowEmbeddedThumb && contentUri != null) {
            val thumb = runCatching {
                context.contentResolver.loadThumbnail(contentUri, Size(request.targetPx, request.targetPx), null)
            }.getOrNull()
            if (thumb != null) {
                if (!ref.isVideo || request.videoFrames <= 1) return@withContext listOf(thumb)
                val extraFrames = runCatching {
                    BitmapLoader.videoFrames(request.targetPx, request.videoFrames - 1) { it.setDataSource(file.absolutePath) }
                }.getOrDefault(emptyList())
                return@withContext listOf(thumb) + extraFrames
            }
        }
        if (!file.isFile) return@withContext emptyList()
        if (ref.isVideo) {
            return@withContext BitmapLoader.videoFrames(request.targetPx, request.videoFrames) { it.setDataSource(file.absolutePath) }
        }
        if (request.allowEmbeddedThumb && ref.mimeType == "image/jpeg") {
            BitmapLoader.exifThumbnail(file, minPx = 120)?.let { return@withContext listOf(it) }
        }
        listOf(BitmapLoader.decodeFile(file, request.targetPx))
    }

    override suspend fun viewTarget(ref: MediaRef, videoStream: Boolean): ViewTarget =
        ViewTarget.Local(Uri.fromFile(File(ref.itemId)))

    override suspend fun delete(ref: MediaRef): DeleteResult = withContext(Dispatchers.IO) {
        val file = File(ref.itemId)
        when {
            !file.exists() -> DeleteResult.Deleted
            file.delete() -> {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                DeleteResult.Deleted
            }
            else -> DeleteResult.Failed("Sistema recusou apagar ${file.absolutePath}")
        }
    }

    companion object {
        const val ID = "local"
        private val SKIP_DIRS = listOf("Android/data", "Android/obb", ".Trash", "LOST.DIR")
    }
}
