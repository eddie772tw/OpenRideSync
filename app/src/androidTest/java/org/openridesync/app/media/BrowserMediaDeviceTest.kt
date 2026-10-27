package org.openridesync.app.media

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The service lives in the test APK's separate process; assertions use its real session token. */
@RunWith(AndroidJUnit4::class)
class BrowserMediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }

    private fun awaitCondition(check: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return true
            SystemClock.sleep(25)
        }
        return check()
    }

    private fun requireListener() {
        val component = ComponentName(target, MediaAccessService::class.java)
        assumeTrue("Notification listener access must already be user granted",
            target.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true)
    }

    @Test fun browserOnlyInactiveTokenPlaysWithoutClaimingPlaybackAndNeverSwitches() {
        requireListener()
        val component = ComponentName(target, MediaAccessService::class.java)
        val manager = target.getSystemService(MediaSessionManager::class.java)
        assumeTrue("Media session manager unavailable", manager != null)
        val closed = CountDownLatch(1)
        val otherPlayed = AtomicReference<Boolean?>()
        val startedAt = SystemClock.elapsedRealtime()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BrowserFixtureService.CLOSED &&
                    intent.getLongExtra(BrowserFixtureService.CREATED_AT, 0L) >= startedAt) {
                    otherPlayed.set(intent.getBooleanExtra(BrowserFixtureService.OTHER_PLAYED, true))
                    closed.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(target, receiver, IntentFilter(BrowserFixtureService.CLOSED), ContextCompat.RECEIVER_EXPORTED)
        var media: NativeMedia? = null
        try {
            val source = main { NativeMedia(target).also { it.start(); it.connectBrowser(fixturePackage) } }
            media = source
            val connected = awaitCondition {
                main { source.browserStatus.value == MediaBrowserStatus.CONNECTED }
            }
            assertTrue("The exported test browser must connect; final status=${main { source.browserStatus.value }}", connected)
            val token = main {
                source.refresh()
                assertNull(source.selectedToken())
                assertTrue(source.selectOnlyPackage(fixturePackage))
                checkNotNull(source.selectedToken())
            }
            assertTrue("The browser token must stay outside active-manager sessions",
                main { manager!!.getActiveSessions(component).none { it.sessionToken == token } })
            val controller = MediaController(target, token)
            assertEquals(fixturePackage, controller.packageName)
            assertEquals(0L, checkNotNull(controller.playbackState).position)
            assertEquals(MediaPlayback.PAUSED, main { source.state.value.playback })
            assertEquals(DispatchResult.DISPATCHED, main { source.play() })
            assertTrue("PLAY must reach the separate test process", awaitCondition {
                controller.playbackState?.position == 1L
            })
            assertEquals("The fixture did not report audible playback", MediaPlayback.PAUSED,
                main { source.refresh(); source.state.value.playback })

            val selectedId = main { source.state.value.selectedId }
            controller.transportControls.sendCustomAction(BrowserFixtureService.ACTIVATE_PRIMARY, null)
            assertTrue("The same browser token must also become manager-active", awaitCondition {
                main { manager!!.getActiveSessions(component).any { it.sessionToken == token } }
            })
            main {
                source.refresh()
                assertEquals(1, source.state.value.sessions.count { it.packageName == fixturePackage })
                assertEquals(selectedId, source.state.value.selectedId)
            }
            controller.transportControls.sendCustomAction(BrowserFixtureService.DEACTIVATE_PRIMARY, null)
            assertTrue("Removing only the manager source must retain browser selection", awaitCondition {
                main {
                    source.refresh()
                    manager!!.getActiveSessions(component).none { it.sessionToken == token } &&
                        source.selectedToken() == token
                }
            })

            val before = main { manager!!.getActiveSessions(component).map { it.sessionToken }.toSet() }
            controller.transportControls.sendCustomAction(BrowserFixtureService.SPAWN_OTHER, null)
            val otherToken = AtomicReference<android.media.session.MediaSession.Token?>()
            assertTrue("A second session from the same package must become active", awaitCondition {
                val added = main { manager!!.getActiveSessions(component).filter {
                    it.packageName == fixturePackage && it.sessionToken !in before && it.sessionToken != token
                } }
                if (added.size == 1) otherToken.set(added.single().sessionToken)
                added.size == 1
            })
            main {
                source.refresh()
                assertFalse("Two exact tokens are ambiguous for automatic selection", source.selectOnlyPackage(fixturePackage))
                assertEquals(token, source.selectedToken())
            }
            assertEquals(0L, checkNotNull(MediaController(target, checkNotNull(otherToken.get())).playbackState).position)
            controller.transportControls.sendCustomAction(BrowserFixtureService.DESTROY_PRIMARY, null)
            assertTrue("Destroying the exact browser session must clear selection", awaitCondition {
                main { source.refresh(); source.selectedToken() == null }
            })
            assertFalse(main { source.selectToken(token) })
            assertNotEquals(DispatchResult.DISPATCHED, main { source.play() })
            assertTrue("Destroying the browser token must release its binding", closed.await(5, TimeUnit.SECONDS))
            assertEquals("The other session must not receive PLAY", false, otherPlayed.get())
        } finally {
            try { main { media?.close() } }
            finally { target.unregisterReceiver(receiver) }
        }
    }

    @Test fun closingConnectedOwnerReleasesBrowserBinding() {
        requireListener()
        val closed = CountDownLatch(1)
        val startedAt = SystemClock.elapsedRealtime()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BrowserFixtureService.CLOSED &&
                    intent.getLongExtra(BrowserFixtureService.CREATED_AT, 0L) >= startedAt) closed.countDown()
            }
        }
        ContextCompat.registerReceiver(target, receiver, IntentFilter(BrowserFixtureService.CLOSED), ContextCompat.RECEIVER_EXPORTED)
        var media: NativeMedia? = null
        try {
            val source = main { NativeMedia(target).also { it.start(); it.connectBrowser(fixturePackage) } }
            media = source
            val connected = awaitCondition {
                main { source.browserStatus.value == MediaBrowserStatus.CONNECTED }
            }
            assertTrue("The browser must connect before explicit close; final status=${main { source.browserStatus.value }}", connected)
            main { source.close() }
            assertTrue("Explicit close must release browser binding", closed.await(5, TimeUnit.SECONDS))
            main {
                assertNotEquals(MediaBrowserStatus.CONNECTED, source.browserStatus.value)
                assertNull(source.selectedToken())
            }
        } finally {
            try { main { media?.close() } }
            finally { target.unregisterReceiver(receiver) }
        }
    }

    @Test fun closingDuringConnectCannotReviveBrowserOrSelection() {
        requireListener()
        val source = main { NativeMedia(target).also { it.start(); it.connectBrowser(fixturePackage); it.close() } }
        assertTrue("A late connection callback must not revive a closed owner", awaitCondition {
            main {
                source.browserStatus.value != MediaBrowserStatus.CONNECTED &&
                    source.state.value.sessions.none { it.packageName == fixturePackage } &&
                    source.selectedToken() == null
            }
        })
        SystemClock.sleep(1_000)
        main {
            assertNotEquals(MediaBrowserStatus.CONNECTED, source.browserStatus.value)
            assertNull(source.selectedToken())
        }
    }
}
