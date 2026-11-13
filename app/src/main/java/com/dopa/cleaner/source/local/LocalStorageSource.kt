package com.dopa.cleaner.source.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
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

    private fun skipDir(dir: File): Boolean {
        val name = dir.name
        if (options.skipThumbnailDirs && name.equals(".thumbnails", ignoreCase = true)) return true
        if (options.skipHiddenDirs && name.startsWith(".")) return true
        return false
    }

    override fun enumerate(): Flow<MediaRef> = flow {
        if (!Environment.isExternalStorageManager()) {
            throw SecurityException("Permissão 'Acesso a todos os arquivos' não concedida")
        }
        val minBytes = options.minFileKb.toLong() * 1024
        for ((label, root) in roots()) {
            val rootPath = root.absolutePath
            val blocked = SKIP_DIRS.map { File(root, it).absolutePath }.toSet()
            val stack = ArrayDeque<File>().apply { add(root) }
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (f in children) {
                    if (f.isDirectory) {
                        if (f.absolutePath in blocked || skipDir(f)) continue
                        if (runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(true)) continue
                        stack.add(f)
                        continue
                    }
                    val mime = MediaTypes.fromName(f.name) ?: continue
                    val len = f.length()
                    if (len < minBytes) continue
                    emit(
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
    }.flowOn(Dispatchers.IO)

    override suspend fun loadFrames(ref: MediaRef, request: FrameRequest): List<Bitmap> = withContext(Dispatchers.IO) {
        val file = File(ref.itemId)
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
