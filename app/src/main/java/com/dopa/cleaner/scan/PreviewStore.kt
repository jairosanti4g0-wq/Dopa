package com.dopa.cleaner.scan

import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

class PreviewStore(private val dir: File) {

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    fun save(key: String, bitmap: Bitmap): File? = runCatching {
        if (!dir.exists() && !dir.mkdirs()) return null
        val f = fileFor(key)
        f.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)) return null
        }
        f
    }.getOrNull()

    fun remove(key: String) {
        runCatching { fileFor(key).delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }
}
