package org.openridesync.app.media

import android.app.NotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.openridesync.app.ui.ShellStatus
import org.openridesync.app.ui.homeOverview

/** Controls only the uniquely identified session created by this test. No real music is played.
 * With existing user-granted access, normal NativeMedia listing may transiently observe other
 * sessions/titles. This test never selects/controls those sessions or copies their metadata into
 * assertions, logs or files; only this package's ephemeral baseline IDs/tokens are compared. */
@RunWith(AndroidJUnit4::class)
class NativeMediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(action()) }
        return result.get()
    }
    private fun awaitCondition(timeoutMs: Long = 5_000, check: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return true
            SystemClock.sleep(25) // Instrumentation thread; never blocks main callbacks.
        }
        return check()
    }

    @Test fun ownedInactiveMediaStyleTokenCanBeSelectedAndRemovalDoesNotSwitchSessions() {
        val context = instrumentation.targetContext
        val component = ComponentName(context, MediaAccessService::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Existing notification posting and listener access required",
            notifications?.areNotificationsEnabled() == true && notifications.isNotificationListenerAccessGranted(component))
        val manager = context.getSystemService(MediaSessionManager::class.java)
        assumeTrue("Media session manager unavailable", manager != null)
        val channel = "ors-synthetic-media-${UUID.randomUUID()}"
        val tag = UUID.randomUUID().toString()
        var owned: MediaSession? = null
        var backend: NativeMedia? = null
        val commands = AtomicInteger()
        try {
            notifications!!.createNotificationChannel(NotificationChannel(channel, "Synthetic media test", NotificationManager.IMPORTANCE_LOW))
            val session = main { MediaSession(context, tag).also {
                it.setCallback(object : MediaSession.Callback() { override fun onPlay() { commands.incrementAndGet() } }, Handler(Looper.getMainLooper()))
                it.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY)
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build())
                it.isActive = false
            } }
            owned = session
            assertTrue(main { manager!!.getActiveSessions(component).none { it.sessionToken == session.sessionToken } })
            val media = main { NativeMedia(context).also { it.start() } }
            backend = media
            // Instrumentation restarts the target; allow the platform's delayed listener rebind.
            // Notification/session changes below still have their original 5-second deadline.
            assertTrue("Granted notification listener must reconnect before notification-source test", awaitCondition(20_000) {
                main { MediaAccessService.callSummary.value.listenerConnected }
            })
            assertFalse(main { media.selectToken(session.sessionToken) })
            val baselineIds = main { media.state.value.sessions.filter { it.packageName == context.packageName }.map { it.id }.toSet() }
            fun notification() = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Synthetic media only").setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)).build()
            notifications.notify(tag, 1, notification())
            assertTrue("Owned notification must be posted with its exact MediaStyle token", awaitCondition {
                notifications.activeNotifications.any {
                    it.tag == tag && it.id == 1 && notificationMediaSession(it)?.token == session.sessionToken
                }
            })
            assertTrue("Owned MediaStyle token must become discoverable without active-manager membership", awaitCondition {
                main { media.refresh(); MediaAccessService.notificationMediaSessions.any { it.token == session.sessionToken } }
            })
            assertTrue("The test must exercise the notification-only source",
                main { manager!!.getActiveSessions(component).none { it.sessionToken == session.sessionToken } })
            main {
                assertNull(media.selectedToken()) // Discovery must not select or play.
                assertTrue(media.selectToken(session.sessionToken))
                assertTrue(media.selectedToken() == session.sessionToken)
            }
            val selectedId = main { media.state.value.selectedId }
            fun assertSameSelection() = main {
                media.refresh()
                assertTrue(media.selectedToken() == session.sessionToken)
                assertEquals(selectedId, media.state.value.selectedId)
                val addedIds = media.state.value.sessions.filter { it.packageName == context.packageName }.map { it.id }.toSet() - baselineIds
                assertEquals(setOf(selectedId), addedIds)
            }
            notifications.notify(tag, 2, notification()) // Duplicate notification must preserve the same exact token.
            main { session.isActive = true }
            assertTrue(awaitCondition { main { manager!!.getActiveSessions(component).any { it.sessionToken == session.sessionToken } } })
            assertSameSelection() // Both discovery sources must still produce one controller/ID.
            main { session.isActive = false }
            assertTrue(awaitCondition { main { manager!!.getActiveSessions(component).none { it.sessionToken == session.sessionToken } } })
            assertSameSelection() // Notification token retains the exact selection after manager removal.
            assertEquals(0, commands.get())
            notifications.cancel(tag, 1)
            notifications.cancel(tag, 2)
            assertTrue("Removing the final source must invalidate selection", awaitCondition {
                main { media.refresh(); media.selectedToken() == null && !media.selectToken(session.sessionToken) }
            })
            assertEquals(0, commands.get())
        } finally {
            notifications?.cancel(tag, 1); notifications?.cancel(tag, 2)
            main { try { backend?.close() } finally { owned?.release() } }
            notifications?.deleteNotificationChannel(channel)
        }
    }

    @Test fun exactTokenHandoffDoesNotSelectAnotherSessionFromSamePackage() {
        val context = instrumentation.targetContext
        val component = ComponentName(context, MediaAccessService::class.java)
        assumeTrue("Notification access must already be user granted",
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(component) == true)
        val manager = context.getSystemService(MediaSessionManager::class.java)
        assumeTrue("Media session manager unavailable", manager != null)
        assumeTrue("No unrelated session from this test package may be selected",
            main { manager!!.getActiveSessions(component).none { it.packageName == context.packageName } })
        var source: NativeMedia? = null
        var destination: NativeMedia? = null
        var first: MediaSession? = null
        var other: MediaSession? = null
        try {
            val played = CountDownLatch(1)
            val otherCommands = AtomicInteger()
            val a = main { MediaSession(context, "OpenRideSync-handoff-A-${UUID.randomUUID()}") }
            first = a
            val b = main { MediaSession(context, "OpenRideSync-handoff-B-${UUID.randomUUID()}") }
            other = b
            main {
                val state = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PAUSED, 0L, 0f).build()
                a.setCallback(object : MediaSession.Callback() { override fun onPlay() { played.countDown() } }, Handler(Looper.getMainLooper()))
                b.setCallback(object : MediaSession.Callback() { override fun onPlay() { otherCommands.incrementAndGet() } }, Handler(Looper.getMainLooper()))
                a.setPlaybackState(state); b.setPlaybackState(state)
                a.isActive = true; b.isActive = true
            }
            assumeTrue("Both owned sessions must become active", awaitCondition {
                main {
                    val tokens = manager!!.getActiveSessions(component).map { it.sessionToken }
                    a.sessionToken in tokens && b.sessionToken in tokens
                }
            })
            val from = main { NativeMedia(context).also { it.start() } }
            source = from
            val token = main {
                assertNull(from.selectedToken())
                assertTrue(from.selectToken(a.sessionToken))
                checkNotNull(from.selectedToken())
            }
            // Compare without printing token contents, including on assertion failure.
            assertTrue(main { token == a.sessionToken })
            main { from.close(); assertNull(from.selectedToken()) }
            val to = main { NativeMedia(context).also { it.start() } }
            destination = to
            main {
                assertNull(to.selectedToken())
                assertFalse("A favorite package with two sessions is ambiguous", to.selectOnlyPackage(context.packageName))
                assertNull(to.selectedToken())
                assertTrue(to.selectToken(token))
                assertTrue(to.selectedToken() == a.sessionToken)
                assertEquals(DispatchResult.DISPATCHED, to.toggle())
            }
            assertTrue("Only the owned target must receive play", played.await(5, TimeUnit.SECONDS))
            assertEquals(0, otherCommands.get())
            main { b.isActive = false }
            assertTrue("Only the first owned session remains", awaitCondition {
                main { manager!!.getActiveSessions(component).none { it.sessionToken == b.sessionToken } }
            })
            main {
                assertTrue("A sole exact-package session may be selected", to.selectOnlyPackage(context.packageName))
                assertTrue(to.selectedToken() == a.sessionToken)
            }
            main { b.isActive = true }
            assertTrue("The other owned session must be available for fallback detection", awaitCondition {
                main { manager!!.getActiveSessions(component).any { it.sessionToken == b.sessionToken } }
            })
            main { a.isActive = false }
            assertTrue("Inactive exact target must not fall back to the other session", awaitCondition {
                main { to.refresh(); !to.selectToken(token) && to.selectedToken() == null }
            })
            assertEquals(0, otherCommands.get())
        } finally {
            main {
                try { source?.close(); destination?.close() }
                finally {
                    try { first?.isActive = false; first?.release() }
                    finally { other?.isActive = false; other?.release() }
                }
            }
        }
    }

    @Test fun ownSessionReceivesPlayButPlaybackRequiresSeparatePublication() {
        val context = instrumentation.targetContext
        val component = ComponentName(context, MediaAccessService::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Notification listener access must already be granted by the user",
            notifications?.isNotificationListenerAccessGranted(component) == true)
        val manager = context.getSystemService(MediaSessionManager::class.java)
        assumeTrue("Media session manager unavailable", manager != null)
        var backend: NativeMedia? = null
        var session: MediaSession? = null
        try {
            val media = main { NativeMedia(context).also { it.start() } }
            backend = media
            assumeTrue("Authorized session access unavailable", main { media.state.value.access == MediaAccess.GRANTED })
            // Store only ephemeral IDs/tokens belonging to this package, never existing metadata.
            val baselineIds = main { media.state.value.sessions.filter { it.packageName == context.packageName }.map { it.id }.toSet() }
            val baselineTokens = main { manager!!.getActiveSessions(component).filter { it.packageName == context.packageName }.map { it.sessionToken }.toSet() }
            val played = CountDownLatch(1)
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            fun playback(value: Int) = PlaybackState.Builder().setActions(actions).setState(value, 0L, 0f).build()
            val owned = main { MediaSession(context, "OpenRideSync-synthetic-${UUID.randomUUID()}") }
            session = owned
            main {
                owned.apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPlay() { played.countDown() } // Intentionally remains PAUSED.
                    }, Handler(Looper.getMainLooper()))
                    setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Synthetic test only").build())
                    setPlaybackState(playback(PlaybackState.STATE_PAUSED))
                    isActive = true
                }
            }
            val verifiedId = AtomicReference<String?>()
            assumeTrue("Test session did not become uniquely visible", awaitCondition {
                main {
                    media.refresh()
                    val newIds = media.state.value.sessions.filter { it.packageName == context.packageName && it.id !in baselineIds }
                    val newTokens = manager!!.getActiveSessions(component).filter { it.packageName == context.packageName }
                        .map { it.sessionToken }.toSet() - baselineTokens
                    val unique = newIds.size == 1 && newTokens == setOf(owned.sessionToken)
                    if (unique) verifiedId.set(newIds.single().id)
                    unique
                }
            })
            // Never reselect from a later list: a vanished verified ID must fail select instead.
            val selected = verifiedId.get()
            assumeTrue("Cannot uniquely identify test session", selected != null)
            main { assertNull(media.selectedToken()); owned.setPlaybackState(playback(PlaybackState.STATE_PLAYING)) }
            assertTrue("Unselected playback callbacks must reach the home observer", awaitCondition {
                main { media.state.value.sessions.singleOrNull { it.id == selected }?.playback == MediaPlayback.PLAYING }
            })
            main {
                val observed = media.state.value.copy(sessions = media.state.value.sessions.filter { it.id == selected })
                val home = homeOverview(ShellStatus(true, "Synthetic signed in", "Idle"),
                    media = MediaPageState(media = observed, signedIn = true, observing = true))
                assertEquals("Synthetic test only", home.media.value)
                assertTrue(home.media.detail.endsWith("播放中"))
                assertNull(media.selectedToken())
                assertEquals("Observation must not send play", 1L, played.count)
                owned.setPlaybackState(playback(PlaybackState.STATE_PAUSED))
            }
            assertTrue("Unselected pause callback must also be published", awaitCondition {
                main { media.state.value.sessions.singleOrNull { it.id == selected }?.playback == MediaPlayback.PAUSED }
            })
            assertTrue(main { media.select(selected!!) })
            assertEquals(MediaPlayback.PAUSED, main { media.state.value.playback })
            assertEquals(DispatchResult.DISPATCHED, main { media.play() })
            assertTrue("Synthetic session did not receive request", played.await(5, TimeUnit.SECONDS))
            assertEquals("Dispatch must not invent PLAYING", MediaPlayback.PAUSED, main { media.state.value.playback })
            main { owned.setPlaybackState(playback(PlaybackState.STATE_PLAYING)) }
            assertTrue("Published synthetic playback was not observed", awaitCondition {
                main { media.state.value.playback == MediaPlayback.PLAYING }
            })
        } finally {
            main {
                try { backend?.close() }
                finally { try { session?.isActive = false } finally { session?.release() } }
            }
        }
    }

    @Test fun unselectedHomeControlsStayBoundToOneOwnedSessionAndObservedActions() {
        val context = instrumentation.targetContext
        val component = ComponentName(context, MediaAccessService::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Existing notification listener grant required",
            notifications?.isNotificationListenerAccessGranted(component) == true)
        val manager = context.getSystemService(MediaSessionManager::class.java)
        assumeTrue("Media session manager unavailable", manager != null)
        var backend: NativeMedia? = null
        var first: MediaSession? = null
        var second: MediaSession? = null
        val pause = AtomicInteger()
        val next = AtomicInteger()
        val previous = AtomicInteger()
        val other = AtomicInteger()
        try {
            val media = main { NativeMedia(context).also { it.start() } }
            backend = media
            assumeTrue("Authorized session access unavailable",
                main { media.state.value.access == MediaAccess.GRANTED })
            val baseline = main { media.state.value.sessions.map { it.id }.toSet() }
            val baselineTokens = main { manager!!.getActiveSessions(component).map { it.sessionToken }.toSet() }
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            fun playing() = PlaybackState.Builder().setActions(actions)
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f).build()
            val a = main { MediaSession(context, "OpenRideSync-home-A-${UUID.randomUUID()}") }
            first = a
            main {
                a.setCallback(object : MediaSession.Callback() {
                    override fun onPause() { pause.incrementAndGet() }
                    override fun onSkipToNext() { next.incrementAndGet() }
                    override fun onSkipToPrevious() { previous.incrementAndGet() }
                }, Handler(Looper.getMainLooper()))
                a.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Owned song")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Owned artist")
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Owned album").build())
                a.setPlaybackState(playing()); a.isActive = true
            }
            val id = AtomicReference<String?>()
            assumeTrue("Owned playing session must be unique; unrelated sessions remain untouched", awaitCondition {
                main {
                    media.refresh()
                    val added = media.state.value.sessions.filter {
                        it.packageName == context.packageName && it.id !in baseline
                    }
                    val newTokens = manager!!.getActiveSessions(component)
                        .filter { it.packageName == context.packageName }
                        .map { it.sessionToken }.toSet() - baselineTokens
                    val exact = added.singleOrNull()?.takeIf { newTokens == setOf(a.sessionToken) }
                    val target = media.state.value.homeControlTarget()
                    if (exact != null && target?.id == exact.id) id.set(exact.id)
                    id.get() != null
                }
            })
            val ownedId = checkNotNull(id.get())
            main {
                val choice = media.state.value.homeControlTarget()!!
                assertEquals(ownedId, choice.id)
                assertEquals("Owned song", choice.title)
                assertEquals("Owned artist", choice.artist)
                assertEquals("Owned album", choice.album)
                assertEquals(actions, choice.actions)
                assertNull(media.state.value.selectedId)
                assertEquals(DispatchResult.DISPATCHED, media.toggle(ownedId))
                assertEquals(DispatchResult.DISPATCHED, media.next(ownedId))
                assertEquals(DispatchResult.DISPATCHED, media.previous(ownedId))
                assertNull(media.state.value.selectedId)
            }
            assertTrue("Only owned callbacks receive requests", awaitCondition {
                pause.get() == 1 && next.get() == 1 && previous.get() == 1
            })
            val b = main { MediaSession(context, "OpenRideSync-home-B-${UUID.randomUUID()}") }
            second = b
            main {
                b.setCallback(object : MediaSession.Callback() {
                    override fun onPause() { other.incrementAndGet() }
                    override fun onSkipToNext() { other.incrementAndGet() }
                }, Handler(Looper.getMainLooper()))
                b.setPlaybackState(playing()); b.isActive = true
            }
            assertTrue("Two owned PLAYING sources must be visible", awaitCondition {
                main {
                    media.refresh()
                    media.state.value.sessions.count {
                        it.packageName == context.packageName && it.id !in baseline
                    } == 2
                }
            })
            main {
                assertNull(media.state.value.homeControlTarget())
                assertEquals(DispatchResult.STALE, media.next(ownedId))
                assertNull(media.state.value.selectedId)
                b.isActive = false; b.release()
                a.isActive = false; a.release()
            }
            second = null; first = null
            assertTrue("Released exact source must disappear", awaitCondition {
                main { media.refresh(); media.state.value.sessions.none { it.id == ownedId } }
            })
            assertEquals(DispatchResult.STALE, main { media.previous(ownedId) })
            assertEquals(1, next.get())
            assertEquals(1, previous.get())
            assertEquals(0, other.get())
        } finally {
            main {
                try { backend?.close() }
                finally {
                    try { first?.isActive = false; first?.release() }
                    finally { second?.isActive = false; second?.release() }
                }
            }
        }
    }
}
