package com.dopa.cleaner.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

object BitmapLoader {
    private const val VIDEO_SAMPLES = 3

    private fun ImageDecoder.Source.decodeScaled(targetPx: Int): Bitmap =
        ImageDecoder.decodeBitmap(this) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > targetPx && w > 0 && h > 0) {
                val scale = targetPx.toFloat() / longest
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }

    fun decodeFile(file: File, targetPx: Int): Bitmap =
        ImageDecoder.createSource(file).decodeScaled(targetPx)

    fun decodeBytes(bytes: ByteArray, targetPx: Int): Bitmap =
        ImageDecoder.createSource(ByteBuffer.wrap(bytes)).decodeScaled(targetPx)

    fun decodeUri(context: Context, uri: Uri, targetPx: Int): Bitmap =
        ImageDecoder.createSource(context.contentResolver, uri).decodeScaled(targetPx)

    fun videoFrames(targetPx: Int, configure: (MediaMetadataRetriever) -> Unit): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        try {
            configure(retriever)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val positions = if (durationMs <= 0L) {
                listOf(0L)
            } else {
                (1..VIDEO_SAMPLES).map { i -> durationMs * 1000L * i / (VIDEO_SAMPLES + 1) }
            }
            return positions.mapNotNull { us ->
                runCatching {
                    retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetPx, targetPx)
                }.getOrNull()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }
}
