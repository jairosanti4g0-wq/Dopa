package com.dopa.cleaner.source.drive

import android.graphics.Bitmap
import com.dopa.cleaner.auth.GoogleAuthorizer
import com.dopa.cleaner.auth.GoogleScopes
import com.dopa.cleaner.core.BitmapLoader
import com.dopa.cleaner.core.DeleteResult
import com.dopa.cleaner.core.FrameRequest
import com.dopa.cleaner.core.Http
import com.dopa.cleaner.core.HttpStatusException
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.MediaSource
import com.dopa.cleaner.core.ViewTarget
import com.dopa.cleaner.core.expectSuccess
import com.dopa.cleaner.core.readBytes
import com.dopa.cleaner.core.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

class GoogleDriveSource(private val authorizer: GoogleAuthorizer) : MediaSource {
    override val id: String = ID
    override val displayName: String = "Google Drive"
    override val canDelete: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val thumbnails = ConcurrentHashMap<String, String>()

    @Serializable
    private data class FileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String = "",
        val mimeType: String = "",
        val size: String? = null,
        val thumbnailLink: String? = null,
        val md5Checksum: String? = null,
        val modifiedTime: String? = null,
    )

    private suspend fun token(): String = authorizer.tokenOrThrow(listOf(GoogleScopes.DRIVE))

    private fun Request.Builder.auth(token: String) = header("Authorization", "Bearer $token")

    override fun enumerate(): Flow<MediaRef> = flow {
        var pageToken: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "q",
                    "(mimeType contains 'image/' or mimeType contains 'video/') and trashed = false and 'me' in owners",
                )
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,thumbnailLink,md5Checksum,modifiedTime)")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = Http.client.readString(Request.Builder().url(url).auth(token()).build())
            val page = json.decodeFromString<FileList>(body)
            for (f in page.files) {
                if (f.mimeType == "image/svg+xml") continue
                f.thumbnailLink?.let { thumbnails[f.id] = it }
                emit(
                    MediaRef(
                        sourceId = ID,
                        itemId = f.id,
                        name = f.name,
                        mimeType = f.mimeType,
                        sizeBytes = f.size?.toLongOrNull(),
                        location = "Google Drive",
                        version = f.md5Checksum ?: f.modifiedTime,
                    ),
                )
            }
            pageToken = page.nextPageToken
        } while (pageToken != null)
    }.flowOn(Dispatchers.IO)

    override suspend fun loadFrames(ref: MediaRef, request: FrameRequest): List<Bitmap> = withContext(Dispatchers.IO) {
        val targetPx = request.targetPx
        val token = token()
        val thumb = thumbnails[ref.itemId]
        if (thumb != null) {
            val sized = thumb.replace(Regex("=s\\d+$"), "=s$targetPx")
            val bytes = runCatching {
                Http.client.readBytes(Request.Builder().url(sized).auth(token).build())
            }.getOrNull()
            if (bytes != null) return@withContext listOf(BitmapLoader.decodeBytes(bytes, targetPx))
        }
        if (ref.isVideo) return@withContext emptyList()
        val url = "$API/files/${ref.itemId}".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()
        val bytes = Http.client.readBytes(Request.Builder().url(url).auth(token).build())
        listOf(BitmapLoader.decodeBytes(bytes, targetPx))
    }

    override suspend fun viewTarget(ref: MediaRef, videoStream: Boolean): ViewTarget {
        val url = "$API/files/${ref.itemId}".toHttpUrl().newBuilder().addQueryParameter("alt", "media").build()
        return ViewTarget.Remote(url.toString(), mapOf("Authorization" to "Bearer ${token()}"))
    }

    override suspend fun delete(ref: MediaRef): DeleteResult = withContext(Dispatchers.IO) {
        try {
            val body = """{"trashed":true}""".toRequestBody("application/json".toMediaType())
            Http.client.expectSuccess(
                Request.Builder().url("$API/files/${ref.itemId}").auth(token()).patch(body).build(),
            )
            DeleteResult.Trashed
        } catch (e: HttpStatusException) {
            if (e.code == 404) DeleteResult.Deleted else DeleteResult.Failed(e.message ?: "HTTP ${e.code}")
        }
    }

    companion object {
        const val ID = "gdrive"
        private const val API = "https://www.googleapis.com/drive/v3"
    }
}
