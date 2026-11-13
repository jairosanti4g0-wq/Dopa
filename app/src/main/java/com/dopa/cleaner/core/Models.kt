package com.dopa.cleaner.core

import java.io.File

data class MediaRef(
    val sourceId: String,
    val itemId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val location: String,
    val version: String? = null,
) {
    val key: String get() = "$sourceId|$itemId"
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

data class NsfwScores(
    val drawings: Float,
    val hentai: Float,
    val neutral: Float,
    val porn: Float,
    val sexy: Float,
) {
    val unsafe: Float get() = (porn + hentai + sexy).coerceIn(0f, 1f)

    companion object {
        val LABELS = listOf("drawings", "hentai", "neutral", "porn", "sexy")

        fun fromArray(values: FloatArray): NsfwScores {
            require(values.size == LABELS.size) { "Expected ${LABELS.size} scores, got ${values.size}" }
            return NsfwScores(values[0], values[1], values[2], values[3], values[4])
        }

        fun maxOf(list: List<NsfwScores>): NsfwScores? = list.maxByOrNull { it.unsafe }
    }
}

data class Finding(
    val ref: MediaRef,
    val sourceName: String,
    val canDelete: Boolean,
    val scores: NsfwScores,
    val preview: File?,
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object Trashed : DeleteResult
    data class Unsupported(val reason: String) : DeleteResult
    data class Failed(val message: String) : DeleteResult
}

class AuthRequiredException(message: String) : Exception(message)

object MediaTypes {
    private val IMAGE = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif",
    )
    private val VIDEO = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "3gp" to "video/3gpp", "mov" to "video/quicktime", "avi" to "video/x-msvideo", "ts" to "video/mp2t",
        "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
    )

    fun fromName(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return IMAGE[ext] ?: VIDEO[ext]
    }

    fun isSupported(mime: String?): Boolean =
        mime != null && (mime.startsWith("image/") || mime.startsWith("video/")) && mime != "image/svg+xml"
}
