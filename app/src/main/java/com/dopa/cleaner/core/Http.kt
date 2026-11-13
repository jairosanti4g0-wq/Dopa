package com.dopa.cleaner.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    const val MAX_DOWNLOAD_BYTES = 30L * 1024 * 1024
}

class HttpStatusException(val code: Int, message: String) : IOException(message)

suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { runCatching { call.cancel() } }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

suspend fun OkHttpClient.readString(request: Request): String =
    await(request).use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code} ${request.method} ${request.url.encodedPath}: ${body.take(300)}")
        body
    }

suspend fun OkHttpClient.readBytes(request: Request, limit: Long = Http.MAX_DOWNLOAD_BYTES): ByteArray =
    await(request).use { resp ->
        if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code} ${request.method} ${request.url.encodedPath}")
        val body = resp.body ?: throw IOException("Resposta vazia")
        val declared = body.contentLength()
        if (declared > limit) throw IOException("Arquivo excede limite de ${limit / (1024 * 1024)} MB")
        val source = body.source()
        if (!source.request(limit + 1)) {
            source.buffer.readByteArray()
        } else {
            throw IOException("Arquivo excede limite de ${limit / (1024 * 1024)} MB")
        }
    }

suspend fun OkHttpClient.expectSuccess(request: Request) {
    await(request).use { resp ->
        if (!resp.isSuccessful) {
            throw HttpStatusException(resp.code, "HTTP ${resp.code} ${request.method} ${request.url.encodedPath}: ${resp.body?.string().orEmpty().take(300)}")
        }
    }
}
