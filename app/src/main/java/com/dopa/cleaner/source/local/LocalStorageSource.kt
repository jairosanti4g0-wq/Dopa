package com.dopa.cleaner.source.local

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
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
import java.io.File

class LocalStorageSource(private val context: Context) : MediaSource {
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

    override fun enumerate(): Flow<MediaRef> = flow {
        if (!Environment.isExternalStorageManager()) {
            throw SecurityException("Permissão 'Acesso a todos os arquivos' não concedida")
        }
        for ((label, root) in roots()) {
            val rootPath = root.canonicalPath
            val blocked = SKIP_DIRS.map { File(root, it).canonicalPath }.toSet()
            val stack = ArrayDeque<File>().apply { add(root) }
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (f in children) {
                    if (f.isDirectory) {
                        val canon = runCatching { f.canonicalPath }.getOrNull() ?: continue
                        if (canon in blocked || !canon.startsWith(rootPath)) continue
                        stack.add(f)
                    } else if (f.isFile) {
                        val mime = MediaTypes.fromName(f.name) ?: continue
                        val len = f.length()
                        if (len < MIN_BYTES) continue
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
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun loadFrames(ref: MediaRef, targetPx: Int): List<Bitmap> = withContext(Dispatchers.IO) {
        val file = File(ref.itemId)
        if (!file.isFile) return@withContext emptyList()
        if (ref.isVideo) {
            BitmapLoader.videoFrames(targetPx) { it.setDataSource(file.absolutePath) }
        } else {
            listOf(BitmapLoader.decodeFile(file, targetPx))
        }
    }

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
        private const val MIN_BYTES = 4L * 1024
        private val SKIP_DIRS = listOf("Android/data", "Android/obb", ".Trash", "LOST.DIR")
    }
}
