package org.openridesync.app.media

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic MediaSession metadata and real NativeMedia publication, without a player or vehicle. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMediaArtworkTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun selectedEmbeddedArtworkIsBoundedReusedAndClearedAcrossMetadataSessionsAndAccess() = runTest(dispatcher) {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(MediaSessionManager::class.java)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val component = ComponentName(app, MediaAccessService::class.java)
        notifications.setNotificationListenerAccessGranted(component, true)
        val album = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val fallback = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val icon = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val portrait = Bitmap.createBitmap(300, 900, Bitmap.Config.ARGB_8888)
        val first = MediaSession(app, "synthetic-artwork-first")
        val second = MediaSession(app, "synthetic-artwork-second")
        val a = MediaController(app, first.sessionToken)
        val b = MediaController(app, second.sessionToken)
        shadowOf(a).setPackageName(app.packageName)
        shadowOf(b).setPackageName(app.packageName)
        shadowOf(a).setMetadata(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, icon).build())
        shadowOf(b).setMetadata(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, portrait).build())
        shadowOf(manager).addController(a)
        shadowOf(manager).addController(b)
        val media = NativeMedia(app)
        fun metadata(controller: MediaController, value: MediaMetadata?) {
            shadowOf(controller).setMetadata(value)
            shadowOf(controller).executeOnMetadataChanged(value)
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            media.start()
            assertEquals(MediaAccess.GRANTED, media.state.value.access)
            assertNull(media.state.value.artwork) // No automatic selection or app/display-icon fallback.
            assertTrue(media.selectToken(first.sessionToken))
            assertNull(media.state.value.artwork)
            metadata(a, MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "First synthetic track")
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, album)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, fallback).build())
            val scaled = checkNotNull(media.state.value.artwork)
            assertEquals(512, scaled.width)
            assertEquals(256, scaled.height) // ALBUM_ART wins over the smaller ART.
            media.refresh(); media.selectedToken()
            assertSame(scaled, media.state.value.artwork)
            val copy = checkNotNull(album.copy(Bitmap.Config.ARGB_8888, true))
            metadata(a, MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, copy).build())
            assertSame(scaled, media.state.value.artwork) // A parcel-like wrapper does not rescale identical pixels.
            copy.eraseColor(Color.BLUE)
            metadata(a, MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, copy).build())
            assertNotSame(scaled, media.state.value.artwork) // In-place generation changes invalidate the scaled copy.

            metadata(a, MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Second synthetic track")
                .putBitmap(MediaMetadata.METADATA_KEY_ART, fallback).build())
            val small = checkNotNull(media.state.value.artwork)
            assertNotSame(fallback, small)
            assertTrue(small.sameAs(fallback))
            fallback.eraseColor(Color.YELLOW)
            metadata(a, MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, fallback).build())
            val changedSmall = checkNotNull(media.state.value.artwork)
            assertNotSame(small, changedSmall)
            assertEquals(Color.GREEN, small.getPixel(0, 0))
            assertEquals(Color.YELLOW, changedSmall.getPixel(0, 0))
            media.refresh()
            assertSame(changedSmall, media.state.value.artwork)
            metadata(a, MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, icon)
                .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "https://example.invalid/no-download").build())
            assertNull(media.state.value.artwork)
            metadata(a, MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, album).build())
            assertNotNull(media.state.value.artwork)
            assertTrue(media.selectToken(second.sessionToken))
            assertEquals(170, media.state.value.artwork?.width)
            assertEquals(512, media.state.value.artwork?.height)
            metadata(b, null)
            assertNull(media.state.value.artwork)
            metadata(a, MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, fallback).build())
            assertNull(media.state.value.artwork) // An unselected controller callback cannot restore its cover.

            assertTrue(media.selectToken(first.sessionToken))
            assertNotNull(media.state.value.artwork)
            shadowOf(manager).clearControllers(); media.refresh()
            assertNull(media.state.value.selectedId)
            assertNull(media.state.value.artwork)
            shadowOf(manager).addController(a); media.refresh()
            assertTrue(media.selectToken(first.sessionToken))
            notifications.setNotificationListenerAccessGranted(component, false)
            media.refresh()
            assertEquals(MediaAccess.REQUIRED, media.state.value.access)
            assertNull(media.state.value.artwork)
            notifications.setNotificationListenerAccessGranted(component, true)
            media.refresh()
            assertNull(media.state.value.artwork) // A fresh access observation cannot reuse the old selection.
            assertTrue(media.selectToken(first.sessionToken))
            media.close()
            assertNull(media.state.value.artwork)
            for (image in listOf(album, fallback, icon, portrait, scaled, copy)) assertFalse(image.isRecycled)
        } finally {
            media.close()
            first.release(); second.release()
            shadowOf(manager).clearControllers()
            notifications.setNotificationListenerAccessGranted(component, false)
        }
    }
}
