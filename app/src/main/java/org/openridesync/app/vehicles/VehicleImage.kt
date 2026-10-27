package org.openridesync.app.vehicles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.diagnostics.DiagnosticJournal.Event
import org.openridesync.honda.wire.HondaWire.GetImageBinaryRequest
import java.io.File
import kotlin.coroutines.coroutineContext

private data class ImageState(val bitmap: Bitmap? = null, val loading: Boolean = true)

/** Actual Honda media only; identifiers/tokens never appear in filenames or UI errors. */
@Composable
internal fun VehicleImage(imageId: String?, modifier: Modifier = Modifier, requests: VehicleImageRequests, cacheOnly: Boolean = false,
    onColor: ((Color?) -> Unit)? = null, backgroundOnly: Boolean = false) {
    // A missing image has no account/cache/network dependency. Leave any prior keyed
    // image composition immediately, cancelling its producer when the ID disappears.
    if (imageId.isNullOrBlank()) {
        if (!backgroundOnly) Box(modifier, contentAlignment = Alignment.Center) { Text("未提供圖片", Modifier.padding(12.dp)) }
        return
    }
    val app = LocalContext.current.applicationContext as OpenRideSyncApplication
    val session = AuthSession.get()
    val auth by session.state.collectAsStateWithLifecycle()
    val uid = if (auth.status == AuthStatus.SIGNED_IN) session.currentUser()?.uid else null
    val revision = session.revision
    val epoch by requests.epoch.collectAsStateWithLifecycle()
    val changes by requests.changes.collectAsStateWithLifecycle()
    // Reset remembered decoded content immediately when identity or image changes.
    key(uid, revision, imageId, auth.status, cacheOnly) {
        if (uid == null) {
            if (!backgroundOnly) Box(modifier, contentAlignment = Alignment.Center) { Text("圖片不可用：請先登入", Modifier.padding(12.dp)) }
        } else {
            val requestKey = VehicleImageRequests.Key(uid, revision, imageId)
            val state by produceState(ImageState(), uid, revision, imageId, changes, epoch, cacheOnly) {
                value = ImageState()
                try {
                    val bytes = if (cacheOnly) {
                        runInterruptible(Dispatchers.IO) {
                            if (!requests.isCurrent(epoch) || session.revision != revision ||
                                session.currentUser()?.uid != uid || session.state.value.status != AuthStatus.SIGNED_IN)
                                throw CancellationException()
                            VehicleImageCache(File(app.cacheDir, "vehicle-images")).read(uid, imageId)
                        }
                    } else requests.load(requestKey, epoch, readCached = {
                        // An evicted success may re-read disk, but cannot silently issue a new RPC.
                        runInterruptible(Dispatchers.IO) {
                            if (!requests.isCurrent(epoch) || session.revision != revision ||
                                session.currentUser()?.uid != uid || session.state.value.status != AuthStatus.SIGNED_IN)
                                throw CancellationException()
                            VehicleImageCache(File(app.cacheDir, "vehicle-images")).read(uid, imageId)?.also {
                                decode(it).recycle()
                            }
                        }
                    }) {
                    runInterruptible(Dispatchers.IO) {
                        fun checkIdentity() {
                            if (Thread.currentThread().isInterrupted || !requests.isCurrent(epoch) || session.revision != revision ||
                                session.currentUser()?.uid != uid || session.state.value.status != AuthStatus.SIGNED_IN) throw CancellationException()
                        }
                        checkIdentity()
                        val cache = VehicleImageCache(File(app.cacheDir, "vehicle-images"))
                        val cached = try { cache.read(uid, imageId) } catch (_: Exception) { null }
                        val cachedBitmap = cached?.let { try { decode(it) } catch (_: Exception) { null } }
                        if (cachedBitmap != null) {
                            checkIdentity()
                            cachedBitmap.recycle()
                            checkNotNull(cached)
                        } else {
                            if (cached != null) try { cache.discard(uid, imageId) } catch (_: Exception) { }
                            checkIdentity()
                            val bytes = HondaSessionRead(session, app.journal, expectedUid = uid, expectedRevision = revision).use { read ->
                                read.read(Event.HONDA_GET_IMAGE) { client, apiKey, token ->
                                    val response = client.getImageBinary(GetImageBinaryRequest.newBuilder().setImageId(imageId).build(), apiKey, token)
                                    check(response.id == imageId && response.binary.size() in 1..VehicleImageLimits.MAX_BYTES)
                                    response.binary.toByteArray()
                                }
                            }
                            val decoded = decode(bytes)
                            checkIdentity()
                            // Cache is optional; a full/unwritable cache does not discard valid decoded media.
                            try { cache.write(uid, imageId, bytes) } catch (_: Exception) { }
                            checkIdentity()
                            decoded.recycle()
                            bytes
                        }
                    }
                    }
                    val bitmap = bytes?.let { runInterruptible(Dispatchers.IO) { decode(it) } }
                    coroutineContext.ensureActive()
                    if (requests.isCurrent(epoch) && session.revision == revision && session.currentUser()?.uid == uid && session.state.value.status == AuthStatus.SIGNED_IN)
                        value = ImageState(bitmap, loading = false)
                    else value = ImageState(loading = false)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    value = ImageState(loading = false)
                }
            }
            val colorCallback by rememberUpdatedState(onColor)
            LaunchedEffect(state.bitmap) {
                colorCallback?.invoke(state.bitmap?.let { bitmap ->
                    val sample = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
                    val color = Color(sample.getPixel(0, 0))
                    if (sample !== bitmap) sample.recycle()
                    color
                })
            }
            Box(modifier, contentAlignment = Alignment.Center) {
                when {
                    state.bitmap != null -> Image(checkNotNull(state.bitmap).asImageBitmap(), "車型圖片", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                    state.loading -> if (!backgroundOnly) CircularProgressIndicator(Modifier.padding(12.dp))
                    else -> if (!backgroundOnly) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (cacheOnly) "尚無已快取圖片" else "圖片不可用", Modifier.padding(12.dp))
                        if (!cacheOnly) TextButton(onClick = { requests.retry(requestKey, epoch) }) { Text("重試圖片") }
                    }
                }
            }
        }
    }
}

private fun decode(bytes: ByteArray): Bitmap {
    require(bytes.size in 1..VehicleImageLimits.MAX_BYTES)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = VehicleImageLimits.sampleSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    if (bitmap.width.toLong() * bitmap.height > VehicleImageLimits.MAX_DECODED_PIXELS ||
        bitmap.width > VehicleImageLimits.MAX_DIMENSION || bitmap.height > VehicleImageLimits.MAX_DIMENSION) {
        bitmap.recycle()
        error("unsupported image bounds")
    }
    return bitmap
}
