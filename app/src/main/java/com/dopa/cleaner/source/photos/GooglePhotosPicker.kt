package com.dopa.cleaner.source.photos

import android.graphics.Bitmap
import com.dopa.cleaner.auth.GoogleAuthorizer
import com.dopa.cleaner.auth.GoogleScopes
import com.dopa.cleaner.core.BitmapLoader
import com.dopa.cleaner.core.DeleteResult
import com.dopa.cleaner.core.FrameRequest
import com.dopa.cleaner.core.Http
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.MediaSource
import com.dopa.cleaner.core.ViewTarget
import com.dopa.cleaner.core.expectSuccess
import com.dopa.cleaner.core.readBytes
import com.dopa.cleaner.core.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class PickerSession(
    val id: String,
    val pickerUri: String = "",
    val mediaItemsSet: Boolean = false,
    val pollingConfig: PollingConfig? = null,
)

@Serializable
data class PollingConfig(val pollInterval: String? = null, val timeoutIn: String? = null)

@Serializable
data class PickedMediaItem(
    val id: String,
    val type: String = "",
    val mediaFile: PickedFile? = null,
)

@Serializable
data class PickedFile(val baseUrl: String, val mimeType: String = "", val filename: String = "")

@Serializable
private data class PickedPage(val mediaItems: List<PickedMediaItem> = emptyList(), val nextPageToken: String? = null)

class GooglePhotosPickerClient(private val authorizer: GoogleAuthorizer) {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun token() = authorizer.tokenOrThrow(listOf(GoogleScopes.PHOTOS_PICKER))

    suspend fun createSession(): PickerSession = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$API/sessions")
            .header("Authorization", "Bearer ${token()}")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        json.decodeFromString<PickerSession>(Http.client.readString(req))
    }

    suspend fun awaitSelection(sessionId: String, timeoutMs: Long = 15 * 60 * 1000L): PickerSession {
        val start = System.currentTimeMillis()
        while (true) {
            val s = getSession(sessionId)
            if (s.mediaItemsSet) return s
            if (System.currentTimeMillis() - start > timeoutMs) throw IllegalStateException("Tempo esgotado aguardando seleção no Google Fotos")
            val interval = s.pollingConfig?.pollInterval?.removeSuffix("s")?.toDoubleOrNull()?.times(1000)?.toLong() ?: 3000L
            delay(interval.coerceIn(1000L, 10_000L))
        }
    }

    private suspend fun getSession(sessionId: String): PickerSession = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$API/sessions/$sessionId")
            .header("Authorization", "Bearer ${token()}")
            .build()
        json.decodeFromString<PickerSession>(Http.client.readString(req))
    }

    suspend fun listItems(sessionId: String): List<PickedMediaItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<PickedMediaItem>()
        var pageToken: String? = null
        do {
            val url = "$API/mediaItems".toHttpUrl().newBuilder()
                .addQueryParameter("sessionId", sessionId)
                .addQueryParameter("pageSize", "100")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build()
            val page = json.decodeFromString<PickedPage>(Http.client.readString(req))
            out += page.mediaItems
            pageToken = page.nextPageToken
        } while (pageToken != null)
        out
    }

    suspend fun accessToken(): String = token()

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.expectSuccess(
                Request.Builder().url("$API/sessions/$sessionId").header("Authorization", "Bearer ${token()}").delete().build(),
            )
        }
    }

    suspend fun download(baseUrl: String, targetPx: Int): ByteArray = withContext(Dispatchers.IO) {
        Http.client.readBytes(
            Request.Builder().url("$baseUrl=w$targetPx-h$targetPx").header("Authorization", "Bearer ${token()}").build(),
        )
    }

    companion object {
        private const val API = "https://photospicker.googleapis.com/v1"
    }
}

class GooglePhotosSource(
    private val client: GooglePhotosPickerClient,
    private val items: List<PickedMediaItem>,
) : MediaSource {
    override val id: String = ID
    override val displayName: String = "Google Fotos (selecionados)"
    override val canDelete: Boolean = false

    private val byId = items.associateBy { it.id }

    override fun enumerate(): Flow<MediaRef> = items.mapNotNull { item ->
        val file = item.mediaFile ?: return@mapNotNull null
        val mime = file.mimeType.ifEmpty { if (item.type == "VIDEO") "video/mp4" else "image/jpeg" }
        MediaRef(
            sourceId = ID,
            itemId = item.id,
            name = file.filename.ifEmpty { item.id },
            mimeType = mime,
            sizeBytes = null,
            location = "Google Fotos",
            version = item.id,
        )
    }.asFlow()

    override suspend fun loadFrames(ref: MediaRef, request: FrameRequest): List<Bitmap> {
        val base = byId[ref.itemId]?.mediaFile?.baseUrl ?: return emptyList()
        val bytes = client.download(base, request.targetPx)
        return listOf(BitmapLoader.decodeBytes(bytes, request.targetPx))
    }

    override suspend fun viewTarget(ref: MediaRef, videoStream: Boolean): ViewTarget {
        val base = byId[ref.itemId]?.mediaFile?.baseUrl
            ?: throw IllegalStateException("Seleção do Google Fotos expirou: selecione novamente")
        val suffix = if (videoStream) "=dv" else "=w2048-h2048"
        return ViewTarget.Remote(base + suffix, mapOf("Authorization" to "Bearer ${client.accessToken()}"))
    }

    override suspend fun delete(ref: MediaRef): DeleteResult =
        DeleteResult.Unsupported("A API do Google Fotos não permite que apps excluam mídia. Apague \"${ref.name}\" manualmente no app Google Fotos.")

    companion object {
        const val ID = "gphotos"
    }
}
