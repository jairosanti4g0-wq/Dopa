package com.dopa.cleaner.core

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

interface MediaSource {
    val id: String
    val displayName: String
    val canDelete: Boolean

    fun enumerate(): Flow<MediaRef>

    suspend fun loadFrames(ref: MediaRef, targetPx: Int): List<Bitmap>

    suspend fun delete(ref: MediaRef): DeleteResult
}
