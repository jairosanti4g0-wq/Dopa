package com.dopa.cleaner.source.webdav

import android.graphics.Bitmap
import android.util.Xml
import com.dopa.cleaner.core.BitmapLoader
import com.dopa.cleaner.core.DeleteResult
import com.dopa.cleaner.core.Http
import com.dopa.cleaner.core.HttpStatusException
import com.dopa.cleaner.core.MediaRef
import com.dopa.cleaner.core.MediaSource
import com.dopa.cleaner.core.MediaTypes
import com.dopa.cleaner.core.expectSuccess
import com.dopa.cleaner.core.readBytes
import com.dopa.cleaner.core.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder

class WebDavSource(
    override val id: String,
    override val displayName: String,
    baseUrl: String,
    username: String,
    password: String,
) : MediaSource {
    override val canDelete: Boolean = true

    private val root: HttpUrl = baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }.toHttpUrl()
    private val authHeader = Credentials.basic(username, password, Charsets.UTF_8)

    private data class DavEntry(val url: HttpUrl, val isDir: Boolean, val contentType: String?, val length: Long?)

    override fun enumerate(): Flow<MediaRef> = flow {
        val visited = HashSet<String>()
        val queue = ArrayDeque<HttpUrl>().apply { add(root) }
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            if (!visited.add(normalize(dir))) continue
            val entries = runCatching { propfind(dir) }.getOrElse { e ->
                if (dir == root) throw e else emptyList()
            }
            for (e in entries) {
                if (normalize(e.url) == normalize(dir)) continue
                if (!e.url.encodedPath.startsWith(root.encodedPath)) continue
                if (e.isDir) {
                    queue.add(e.url)
                    continue
                }
                val name = URLDecoder.decode(e.url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty(), "UTF-8")
                val mime = e.contentType?.substringBefore(';')?.trim()?.takeIf { MediaTypes.isSupported(it) }
                    ?: MediaTypes.fromName(name)
                    ?: continue
                emit(
                    MediaRef(
                        sourceId = id,
                        itemId = e.url.toString(),
                        name = name,
                        mimeType = mime,
                        sizeBytes = e.length,
                        location = "$displayName: ${URLDecoder.decode(dir.encodedPath.removePrefix(root.encodedPath), "UTF-8").ifEmpty { "/" }}",
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun propfind(dir: HttpUrl): List<DavEntry> {
        val req = Request.Builder()
            .url(dir)
            .header("Authorization", authHeader)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        return parseMultistatus(Http.client.readString(req), dir)
    }

    private fun parseMultistatus(xml: String, base: HttpUrl): List<DavEntry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xml))
        }
        val out = mutableListOf<DavEntry>()
        var href: String? = null
        var isDir = false
        var type: String? = null
        var length: Long? = null
        var text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (parser.name) {
                        "response" -> { href = null; isDir = false; type = null; length = null }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontenttype" -> type = text.toString().trim().ifEmpty { null }
                    "getcontentlength" -> length = text.toString().trim().toLongOrNull()
                    "response" -> {
                        val resolved = href?.let { base.resolve(it) }
                        if (resolved != null) out += DavEntry(resolved, isDir, type, length)
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun normalize(url: HttpUrl): String = url.encodedPath.trimEnd('/')

    override suspend fun loadFrames(ref: MediaRef, targetPx: Int): List<Bitmap> = withContext(Dispatchers.IO) {
        if (ref.isVideo) {
            BitmapLoader.videoFrames(targetPx) { it.setDataSource(ref.itemId, mapOf("Authorization" to authHeader)) }
        } else {
            val bytes = Http.client.readBytes(Request.Builder().url(ref.itemId).header("Authorization", authHeader).build())
            listOf(BitmapLoader.decodeBytes(bytes, targetPx))
        }
    }

    override suspend fun delete(ref: MediaRef): DeleteResult = withContext(Dispatchers.IO) {
        try {
            Http.client.expectSuccess(Request.Builder().url(ref.itemId).header("Authorization", authHeader).delete().build())
            DeleteResult.Deleted
        } catch (e: HttpStatusException) {
            if (e.code == 404) DeleteResult.Deleted else DeleteResult.Failed(e.message ?: "HTTP ${e.code}")
        }
    }

    suspend fun testConnection() {
        withContext(Dispatchers.IO) { propfind(root) }
    }

    companion object {
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontenttype/><d:getcontentlength/></d:prop></d:propfind>"""
    }
}
