package org.openridesync.app.guides

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class GuideDownloadTest {
    @Test fun boundedHtmlPreservesUnicodeAndRejectsEmptyOrOversizedContent() {
        val html = "<!doctype html><title>車輛設定</title><img src='image.png'>"
        assertEquals(html, GuideDownload.readHtml(html.toResponseBody()))
        assertThrows(IOException::class.java) { "".toResponseBody().use { GuideDownload.readHtml(it) } }
        assertThrows(IOException::class.java) { ByteArray(GuideDownload.MAX_BYTES + 1).toResponseBody().use { GuideDownload.readHtml(it) } }
        assertEquals("é", GuideDownload.readHtml(byteArrayOf(0xe9.toByte()).toResponseBody("text/html; charset=ISO-8859-1".toMediaType())))
    }

    @Test fun unicodeBomTakesPrecedenceOverConflictingHttpCharset() {
        val content = "<h1>車輛說明</h1>"
        listOf(
            byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) to Charsets.UTF_8,
            byteArrayOf(0xfe.toByte(), 0xff.toByte()) to Charsets.UTF_16BE,
            byteArrayOf(0xff.toByte(), 0xfe.toByte()) to Charsets.UTF_16LE,
            byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte()) to java.nio.charset.Charset.forName("UTF-32BE"),
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0) to java.nio.charset.Charset.forName("UTF-32LE"),
        ).forEach { (bom, charset) ->
            assertEquals(content, GuideDownload.readHtml((bom + content.toByteArray(charset))
                .toResponseBody("text/html; charset=ISO-8859-1".toMediaType())))
        }
    }

    @Test fun coroutineCancellationCancelsPendingHttpCallAndDiscardsLateFailure() = runBlocking {
        val enqueued = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean()
        var callback: Callback? = null
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { _, method, arguments ->
            when (method.name) {
                "enqueue" -> { callback = arguments!![0] as Callback; enqueued.complete(Unit); null }
                "cancel" -> { cancelled.set(true); null }
                else -> throw AssertionError("unexpected Call method: " + method.name)
            }
        } as Call
        val job = launch { GuideDownload.readCall(call); fail("cancelled HTTP must not publish") }
        withTimeout(5_000) { enqueued.await() }
        job.cancelAndJoin()
        assertTrue(cancelled.get())
        callback!!.onFailure(call, IOException("synthetic late failure"))
        assertTrue(job.isCancelled)
    }
}