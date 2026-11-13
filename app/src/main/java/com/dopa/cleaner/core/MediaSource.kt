package com.dopa.cleaner.core

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.Flow

data class FrameRequest(
    val targetPx: Int,
    val videoFrames: Int,
    val allowEmbeddedThumb: Boolean,
)

sealed interface ViewTarget {
    data class Local(val uri: Uri) : ViewTarget
    data class Remote(val url: String, val headers: Map<String, String>) : ViewTarget
}

interface MediaSource {
    val id: String
    val displayName: String
    val canDelete: Boolean

    fun enumerate(): Flow<MediaRef>

    suspend fun loadFrames(ref: MediaRef, request: FrameRequest): List<Bitmap>

    suspend fun viewTarget(ref: MediaRef, videoStream: Boolean): ViewTarget

    suspend fun delete(ref: MediaRef): DeleteResult
}
