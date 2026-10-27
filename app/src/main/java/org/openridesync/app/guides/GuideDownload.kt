package org.openridesync.app.guides

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class GuideDocument(val url: String, val html: String)

internal sealed interface GuideHttpResult {
    data class Redirect(val location: String) : GuideHttpResult
    data class Html(val value: String) : GuideHttpResult
}

/** Public HTML only: a separate client without credential interceptors, cookie jar, or disk cache. */
internal object GuideDownload {
    const val MAX_BYTES = 4 * 1024 * 1024
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }

    suspend fun fetch(value: String): GuideDocument {
        val original = GuideUrlPolicy.requireHttps(value)
        try {
            return withTimeout(45_000) {
                var uri = original
                repeat(6) { redirects ->
                    val call = client.newCall(Request.Builder().url(uri.toString()).build())
                    when (val result = readCall(call)) {
                        is GuideHttpResult.Redirect -> {
                            if (redirects == 5) throw IOException("guide redirect limit")
                            uri = GuideUrlPolicy.requireHttps(uri.resolve(result.location).toString())
                        }
                        is GuideHttpResult.Html -> return@withTimeout GuideDocument(original.toString(), result.value)
                    }
                }
                throw IOException("guide redirect limit")
            }
        } catch (_: TimeoutCancellationException) { throw TimeoutException("guide download timeout") }
    }

    internal suspend fun readCall(call: Call): GuideHttpResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        when {
                            response.code in setOf(301, 302, 303, 307, 308) ->
                                GuideHttpResult.Redirect(response.header("Location") ?: throw IOException("missing guide redirect"))
                            !response.isSuccessful -> throw IOException("guide HTTP failure")
                            else -> GuideHttpResult.Html(readHtml(response.body))
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    internal fun readHtml(body: ResponseBody): String {
        if (body.contentLength() > MAX_BYTES || body.source().request(MAX_BYTES.toLong() + 1)) throw IOException("guide size limit")
        // ResponseBody handles BOM, HTTP charset, and UTF-8 fallback exactly as the OEM loader.
        return body.string().also { if (it.isBlank()) throw IOException("empty guide") }
    }
}
