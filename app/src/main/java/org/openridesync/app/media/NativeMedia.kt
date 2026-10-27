package org.openridesync.app.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

internal enum class MediaAccess { GRANTED, REQUIRED, UNAVAILABLE }
internal enum class MediaPlayback { UNKNOWN, NONE, STOPPED, PAUSED, PLAYING, BUFFERING, CONNECTING, ERROR, OTHER }
internal enum class DispatchResult { DISPATCHED, UNSUPPORTED, STALE, ACCESS_REQUIRED, UNAVAILABLE }
internal enum class MediaBrowserStatus { NONE, CONNECTING, CONNECTED, UNAVAILABLE, AMBIGUOUS, SUSPENDED, FAILED }
internal data class MediaSessionChoice(val id: String, val label: String, val packageName: String,
    val title: String? = null, val playback: MediaPlayback = MediaPlayback.UNKNOWN,
    val artist: String? = null, val album: String? = null, val actions: Long = 0)
internal data class MediaState(
    val access: MediaAccess = MediaAccess.REQUIRED,
    val sessions: List<MediaSessionChoice> = emptyList(),
    val selectedId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val playback: MediaPlayback = MediaPlayback.UNKNOWN,
    val actions: Long = 0,
    val album: String? = null,
    val artwork: Bitmap? = null,
) {
    val canPlay get() = selectedId != null && access == MediaAccess.GRANTED && actions and PlaybackState.ACTION_PLAY != 0L
    val canPause get() = selectedId != null && access == MediaAccess.GRANTED && actions and PlaybackState.ACTION_PAUSE != 0L
    val canNext get() = selectedId != null && access == MediaAccess.GRANTED && actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
    val canPrevious get() = selectedId != null && access == MediaAccess.GRANTED && actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
}

/** Explicit selection wins; otherwise only one playing session or one total session is actionable. */
internal fun MediaState.homeControlTarget(): MediaSessionChoice? {
    if (access != MediaAccess.GRANTED) return null
    if (selectedId != null) return sessions.singleOrNull { it.id == selectedId }
    return sessions.filter { it.playback == MediaPlayback.PLAYING }.singleOrNull()
        ?: sessions.singleOrNull()
}

/** One bounded, main-thread owner. Session IDs are ephemeral; no persisted token or automatic selection. */
internal class NativeMedia(context: Context, private val journal: DiagnosticJournal? = null) : AutoCloseable {
    private class Session(val choice: MediaSessionChoice, val controller: MediaController, val callback: MediaController.Callback)
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(MediaSessionManager::class.java)
    private val notificationManager = this.context.getSystemService(NotificationManager::class.java)
    private val component = ComponentName(this.context, MediaAccessService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = linkedMapOf<MediaSession.Token, Session>()
    private val destroyed = mutableSetOf<MediaSession.Token>()
    private val mutableState = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = mutableState.asStateFlow()
    private var selectedId: String? = null
    private var artworkOwner: String? = null
    private var artworkSource: Bitmap? = null
    private var artworkGeneration = 0
    private var artworkImage: Bitmap? = null
    private var running = false
    private var closed = false
    private var registered = false
    private var browser: MediaBrowser? = null
    private var browserToken: MediaSession.Token? = null
    private val mutableBrowserStatus = MutableStateFlow(MediaBrowserStatus.NONE)
    val browserStatus: StateFlow<MediaBrowserStatus> = mutableBrowserStatus.asStateFlow()
    private var browserOperation: String? = null
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { if (running) refresh() }

    fun start() {
        mainThread()
        check(!closed) { "Media instance is closed" }
        if (running) return
        running = true
        scope.launch { MediaAccessService.changes.drop(1).collect { refresh() } }
        refresh()
        if (mutableState.value.access == MediaAccess.GRANTED) {
            try { MediaAccessService.rebindIfDisconnected(component) }
            catch (_: RuntimeException) { /* Active-manager access can remain usable while listener rebinding fails. */ }
        }
        // An initial REQUIRED equals the default state, but is still an actual access observation.
        accessEvent(mutableState.value.access)
    }

    /** Only a caller-selected package; no content browsing, credentials or player-specific commands. */
    internal fun connectBrowser(packageName: String) {
        mainThread()
        if (!running || closed || browserStatus.value != MediaBrowserStatus.NONE) return
        refresh()
        if (state.value.access != MediaAccess.GRANTED) return
        browserOperation = journal?.newOperation()
        try {
            val components = context.packageManager.queryIntentServices(
                Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(packageName), 0
            ).mapNotNull { it.serviceInfo }.filter {
                it.packageName == packageName && it.enabled && it.applicationInfo.enabled && it.exported &&
                    (it.permission.isNullOrEmpty() || context.checkSelfPermission(it.permission) == PackageManager.PERMISSION_GRANTED)
            }.map { ComponentName(it.packageName, it.name) }.distinct()
            if (components.size != 1) {
                browserEvent(if (components.isEmpty()) MediaBrowserStatus.UNAVAILABLE else MediaBrowserStatus.AMBIGUOUS)
                return
            }
            lateinit var connection: MediaBrowser
            val callback = object : MediaBrowser.ConnectionCallback() {
                private fun live() = running && !closed && browser === connection
                override fun onConnected() {
                    if (!live()) return
                    try {
                        if (!granted()) { clear(MediaAccess.REQUIRED); return }
                        val token = connection.sessionToken
                        if (MediaController(context, token).packageName != packageName) {
                            stopBrowser(MediaBrowserStatus.FAILED); return
                        }
                        browserToken = token
                        browserEvent(MediaBrowserStatus.CONNECTED)
                        refresh() // A reconnected token is a candidate, never an automatic selection.
                    } catch (_: RuntimeException) { stopBrowser(MediaBrowserStatus.FAILED) }
                }
                override fun onConnectionSuspended() {
                    if (!live()) return
                    browserToken = null
                    browserEvent(MediaBrowserStatus.SUSPENDED)
                    refresh()
                }
                override fun onConnectionFailed() {
                    if (live()) stopBrowser(MediaBrowserStatus.FAILED)
                }
            }
            connection = MediaBrowser(context, components.single(), callback, null)
            browser = connection
            browserEvent(MediaBrowserStatus.CONNECTING)
            connection.connect()
        } catch (_: RuntimeException) { stopBrowser(MediaBrowserStatus.FAILED) }
    }

    /** End a pending acquisition at the caller's deadline; keep ordinary session sources usable. */
    internal fun stopBrowser(status: MediaBrowserStatus = MediaBrowserStatus.NONE) {
        mainThread()
        disconnectBrowser(status)
        refresh()
    }

    private fun disconnectBrowser(status: MediaBrowserStatus) {
        val previous = browser
        browser = null // Fence callbacks before unbinding.
        browserToken = null
        try { previous?.disconnect() } catch (_: RuntimeException) { /* Stale service binder. */ }
        browserEvent(status)
    }

    private fun browserEvent(status: MediaBrowserStatus) {
        if (mutableBrowserStatus.value == status) return
        mutableBrowserStatus.value = status
        val outcome = when (status) {
            MediaBrowserStatus.CONNECTING -> Outcome.STARTED
            MediaBrowserStatus.CONNECTED -> Outcome.SUCCEEDED
            MediaBrowserStatus.NONE -> Outcome.CANCELLED
            MediaBrowserStatus.UNAVAILABLE -> Outcome.UNAVAILABLE
            MediaBrowserStatus.FAILED, MediaBrowserStatus.AMBIGUOUS -> Outcome.FAILED
            MediaBrowserStatus.SUSPENDED -> Outcome.OBSERVED
        }
        val code = when (status) {
            MediaBrowserStatus.FAILED, MediaBrowserStatus.UNAVAILABLE, MediaBrowserStatus.SUSPENDED -> ErrorCode.UNAVAILABLE
            MediaBrowserStatus.AMBIGUOUS -> ErrorCode.FAILED_PRECONDITION
            MediaBrowserStatus.NONE -> ErrorCode.CANCELLED
            else -> ErrorCode.NONE
        }
        journal?.record(Component.MEDIA, browserOperation, Event.MEDIA_BROWSER, outcome, code)
    }

    fun refresh() {
        mainThread()
        if (!running || closed) return
        try {
            if (!granted()) { clear(MediaAccess.REQUIRED); return }
            val service = manager ?: run { clear(MediaAccess.UNAVAILABLE); return }
            if (!registered) {
                service.addOnActiveSessionsChangedListener(sessionListener, component, handler)
                registered = true
            }
            // OEM CompositeMediaSessionProvider: active manager first, then MediaStyle notification tokens.
            // Verify the token's actual controller package before admitting a notification's claimed source.
            MediaAccessService.refreshNotifications()
            val snapshot = service.getActiveSessions(component).distinctBy { it.sessionToken }.toMutableList()
            val knownTokens = snapshot.map { it.sessionToken }.toMutableSet()
            for (notification in MediaAccessService.notificationMediaSessions) {
                if (notification.token in knownTokens) continue
                try {
                    val controller = MediaController(context, notification.token)
                    if (controller.packageName != notification.packageName) continue
                    snapshot.add(controller)
                    knownTokens.add(notification.token)
                } catch (_: RuntimeException) { /* One stale/malformed notification cannot replace valid controllers. */ }
            }
            browserToken?.takeIf { browser?.isConnected == true && it !in knownTokens }?.let { token ->
                try { snapshot.add(MediaController(context, token)) }
                catch (_: RuntimeException) { disconnectBrowser(MediaBrowserStatus.FAILED) }
            }
            destroyed.retainAll(snapshot.map { it.sessionToken }.toSet())
            val active = snapshot.filter { it.sessionToken !in destroyed }
            val tokens = active.map { it.sessionToken }.toSet()
            sessions.keys.filter { it !in tokens }.forEach { remove(it) }
            for (controller in active) {
                val token = controller.sessionToken
                if (token in sessions) continue
                val name = controller.packageName
                val label = (try {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(name, 0)).toString()
                } catch (_: android.content.pm.PackageManager.NameNotFoundException) { name }).let(::boundedMediaText)
                lateinit var session: Session
                val callback = object : MediaController.Callback() {
                    private fun live() = running && !closed && sessions[token] === session
                    override fun onMetadataChanged(metadata: MediaMetadata?) { if (live()) publishSafely() }
                    override fun onPlaybackStateChanged(playback: PlaybackState?) { if (live()) publishSafely() }
                    override fun onSessionDestroyed() {
                        if (!live()) return
                        destroyed.add(token)
                        if (browserToken == token) disconnectBrowser(MediaBrowserStatus.FAILED)
                        remove(token)
                        publishSafely()
                    }
                }
                session = Session(MediaSessionChoice(UUID.randomUUID().toString(), label, name), controller, callback)
                sessions[token] = session
                controller.registerCallback(callback, handler)
            }
            publish()
        } catch (_: SecurityException) { clear(MediaAccess.REQUIRED) }
        catch (_: RuntimeException) { clear(MediaAccess.UNAVAILABLE) }
    }

    fun select(id: String): Boolean {
        mainThread()
        refresh()
        if (closed || !running || mutableState.value.access != MediaAccess.GRANTED || sessions.values.none { it.choice.id == id }) return false
        selectedId = id
        publishSafely()
        return mutableState.value.selectedId == id
    }

    /** Favorite launch may use only one observed session from the saved package. */
    internal fun selectOnlyPackage(packageName: String): Boolean {
        mainThread()
        refresh()
        if (mutableState.value.access != MediaAccess.GRANTED) return false
        val only = sessions.values.singleOrNull { it.choice.packageName == packageName } ?: return false
        return select(only.choice.id)
    }

    /** Same-process, memory-only handoff. Never substitute a package or the first session. */
    internal fun selectedToken(): MediaSession.Token? {
        mainThread()
        refresh()
        if (closed || !running || mutableState.value.access != MediaAccess.GRANTED) return null
        return sessions.entries.singleOrNull { it.value.choice.id == selectedId }?.key
    }

    internal fun selectToken(token: MediaSession.Token): Boolean {
        mainThread()
        refresh()
        if (closed || !running || mutableState.value.access != MediaAccess.GRANTED) return false
        val chosen = sessions[token]?.takeIf { token !in destroyed } ?: return false
        selectedId = chosen.choice.id
        publishSafely()
        return mutableState.value.selectedId == chosen.choice.id
    }

    fun toggle(): DispatchResult {
        mainThread()
        val result = try {
            refresh()
            val current = sessions.values.singleOrNull { it.choice.id == selectedId }
            when {
                closed || !running -> DispatchResult.UNAVAILABLE
                mutableState.value.access == MediaAccess.REQUIRED -> DispatchResult.ACCESS_REQUIRED
                mutableState.value.access != MediaAccess.GRANTED -> DispatchResult.UNAVAILABLE
                current == null -> DispatchResult.STALE
                else -> when (mediaToggleAction(current.controller.playbackState?.state)) {
                    PlaybackState.ACTION_PAUSE -> return pause()
                    PlaybackState.ACTION_PLAY -> return play()
                    else -> DispatchResult.UNSUPPORTED
                }
            }
        } catch (_: SecurityException) { clear(MediaAccess.REQUIRED); DispatchResult.ACCESS_REQUIRED }
        catch (_: RuntimeException) { clear(MediaAccess.UNAVAILABLE); DispatchResult.UNAVAILABLE }
        val operation = journal?.newOperation()
        journal?.record(Component.MEDIA, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
        recordDispatch(operation, Event.REQUEST, result)
        return result
    }

    fun play(): DispatchResult = dispatch(PlaybackState.ACTION_PLAY, Event.MEDIA_PLAY) { this.play() }
    fun pause(): DispatchResult = dispatch(PlaybackState.ACTION_PAUSE, Event.MEDIA_PAUSE) { this.pause() }
    fun next(): DispatchResult = dispatch(PlaybackState.ACTION_SKIP_TO_NEXT, Event.MEDIA_NEXT) { skipToNext() }
    fun previous(): DispatchResult = dispatch(PlaybackState.ACTION_SKIP_TO_PREVIOUS, Event.MEDIA_PREVIOUS) { skipToPrevious() }

    /** Home controls an observed exact token without silently selecting any session. */
    fun previous(id: String): DispatchResult = dispatchObserved(id, Event.MEDIA_PREVIOUS,
        { PlaybackState.ACTION_SKIP_TO_PREVIOUS }) { skipToPrevious() }
    fun next(id: String): DispatchResult = dispatchObserved(id, Event.MEDIA_NEXT,
        { PlaybackState.ACTION_SKIP_TO_NEXT }) { skipToNext() }
    fun toggle(id: String): DispatchResult = dispatchObserved(id, Event.REQUEST,
        { mediaToggleAction(it?.state) }) {
        if (it == PlaybackState.ACTION_PLAY) play() else pause()
    }

    private fun dispatchObserved(id: String, event: Event, actionFor: (PlaybackState?) -> Long?,
        send: MediaController.TransportControls.(Long) -> Unit): DispatchResult {
        mainThread()
        val operation = journal?.newOperation()
        journal?.record(Component.MEDIA, operation, event, Outcome.STARTED, ErrorCode.NONE)
        val before = mutableState.value.homeControlTarget()?.id
        val result = try {
            refresh()
            val entry = sessions.entries.singleOrNull { it.value.choice.id == id }
            val current = entry?.value
            val liveTarget = MediaState(MediaAccess.GRANTED, sessions.values.map {
                it.choice.copy(playback = mediaPlayback(it.controller.playbackState?.state))
            }, selectedId).homeControlTarget()?.id
            when {
                !running || closed -> DispatchResult.UNAVAILABLE
                mutableState.value.access == MediaAccess.REQUIRED -> DispatchResult.ACCESS_REQUIRED
                mutableState.value.access != MediaAccess.GRANTED -> DispatchResult.UNAVAILABLE
                before != id || mutableState.value.homeControlTarget()?.id != id || liveTarget != id ||
                    entry == null || current == null ||
                    entry.key != current.controller.sessionToken ||
                    current.controller.packageName != current.choice.packageName -> DispatchResult.STALE
                else -> {
                    val playback = current.controller.playbackState
                    val action = actionFor(playback)
                    if (action == null || (playback?.actions ?: 0L) and action == 0L) DispatchResult.UNSUPPORTED
                    else { current.controller.transportControls.send(action); DispatchResult.DISPATCHED }
                }
            }
        } catch (_: SecurityException) { clear(MediaAccess.REQUIRED); DispatchResult.ACCESS_REQUIRED }
        catch (_: RuntimeException) { clear(MediaAccess.UNAVAILABLE); DispatchResult.UNAVAILABLE }
        recordDispatch(operation, event, result)
        return result
    }

    private fun dispatch(action: Long, event: Event, send: MediaController.TransportControls.() -> Unit): DispatchResult {
        mainThread()
        val operation = journal?.newOperation()
        journal?.record(Component.MEDIA, operation, event, Outcome.STARTED, ErrorCode.NONE)
        val id = selectedId
        val result = try {
            refresh() // Recheck enabled access and every observed token source immediately before dispatch.
            val current = sessions.values.singleOrNull { it.choice.id == id }
            when {
                !running || closed -> DispatchResult.UNAVAILABLE
                mutableState.value.access == MediaAccess.REQUIRED -> DispatchResult.ACCESS_REQUIRED
                mutableState.value.access != MediaAccess.GRANTED -> DispatchResult.UNAVAILABLE
                id == null || selectedId != id || current == null -> DispatchResult.STALE
                (current.controller.playbackState?.actions ?: 0L) and action == 0L -> DispatchResult.UNSUPPORTED
                else -> { current.controller.transportControls.send(); DispatchResult.DISPATCHED }
            }
        } catch (_: SecurityException) { clear(MediaAccess.REQUIRED); DispatchResult.ACCESS_REQUIRED }
        catch (_: RuntimeException) { clear(MediaAccess.UNAVAILABLE); DispatchResult.UNAVAILABLE }
        recordDispatch(operation, event, result)
        return result
    }

    private fun recordDispatch(operation: String?, event: Event, result: DispatchResult) {
        val code = when (result) {
            DispatchResult.DISPATCHED -> ErrorCode.NONE
            DispatchResult.UNSUPPORTED -> ErrorCode.UNSUPPORTED
            DispatchResult.STALE -> ErrorCode.ABORTED
            DispatchResult.ACCESS_REQUIRED -> ErrorCode.PERMISSION_DENIED
            DispatchResult.UNAVAILABLE -> ErrorCode.UNAVAILABLE
        }
        journal?.record(Component.MEDIA, operation, event,
            if (result == DispatchResult.DISPATCHED) Outcome.OBSERVED else Outcome.FAILED, code)
    }

    private fun granted() = notificationManager?.isNotificationListenerAccessGranted(component) == true
    private fun publishSafely() {
        try { if (!granted()) clear(MediaAccess.REQUIRED) else publish() }
        catch (_: SecurityException) { clear(MediaAccess.REQUIRED) }
        catch (_: RuntimeException) { clear(MediaAccess.UNAVAILABLE) }
    }
    private fun publish() {
        val selected = sessions.values.singleOrNull { it.choice.id == selectedId }
        selectedId = selected?.choice?.id
        val metadataById = sessions.values.associate { it.choice.id to it.controller.metadata }
        val playbackById = sessions.values.associate { it.choice.id to it.controller.playbackState }
        val metadata = metadataById[selectedId]
        val playback = playbackById[selectedId]
        fun title(value: MediaMetadata?) = mediaTitle(value?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString(),
            value?.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.toString())
        fun text(value: MediaMetadata?, key: String) = value?.getText(key)?.toString()?.let(::boundedMediaText)
        val next = MediaState(MediaAccess.GRANTED, sessions.values.map { it.choice.copy(
            title = title(metadataById[it.choice.id]), playback = mediaPlayback(playbackById[it.choice.id]?.state),
            artist = text(metadataById[it.choice.id], MediaMetadata.METADATA_KEY_ARTIST),
            album = text(metadataById[it.choice.id], MediaMetadata.METADATA_KEY_ALBUM),
            actions = playbackById[it.choice.id]?.actions ?: 0L) }, selectedId,
            title(metadata),
            text(metadata, MediaMetadata.METADATA_KEY_ARTIST),
            mediaPlayback(playback?.state), playback?.actions ?: 0,
            text(metadata, MediaMetadata.METADATA_KEY_ALBUM), selectedArtwork(metadata))
        if (next.access != mutableState.value.access) accessEvent(next.access)
        if (next != mutableState.value) journal?.record(Component.MEDIA, null, Event.MEDIA_SESSION, Outcome.OBSERVED, ErrorCode.NONE)
        mutableState.value = next
    }

    /** Only the selected session's embedded album image; never resolve image URIs or app icons. */
    private fun selectedArtwork(metadata: MediaMetadata?): Bitmap? {
        fun bitmap(key: String) = runCatching { metadata?.getBitmap(key)?.takeUnless { it.isRecycled } }.getOrNull()
        val source = bitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: bitmap(MediaMetadata.METADATA_KEY_ART)
        if (source == null) { clearArtwork(); return null }
        try {
            val previous = artworkSource
            // Binder metadata can return a different Bitmap wrapper for unchanged pixels.
            // A generation check also prevents reusing a scaled copy after in-place mutation.
            val unchanged = artworkOwner == selectedId && previous != null && !previous.isRecycled &&
                artworkGeneration == previous.generationId && (previous === source || source.sameAs(previous))
            val image = if (unchanged) artworkImage else {
                val edge = maxOf(source.width, source.height)
                if (edge <= 512) source.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(source,
                    (source.width.toLong() * 512 / edge).toInt().coerceAtLeast(1),
                    (source.height.toLong() * 512 / edge).toInt().coerceAtLeast(1), true)
            }
            artworkOwner = selectedId
            artworkSource = source
            artworkGeneration = source.generationId
            artworkImage = image
            return image
        } catch (_: RuntimeException) { clearArtwork(); return null }
    }

    private fun clearArtwork() {
        // Neither controller-owned images nor copies already published to UI are recycled here.
        artworkOwner = null
        artworkSource = null
        artworkGeneration = 0
        artworkImage = null
    }

    private fun remove(token: MediaSession.Token) {
        val session = sessions.remove(token) ?: return
        if (selectedId == session.choice.id) { selectedId = null; clearArtwork() }
        try { session.controller.unregisterCallback(session.callback) } catch (_: RuntimeException) { /* Stale binder. */ }
    }
    private fun clear(access: MediaAccess) {
        disconnectBrowser(MediaBrowserStatus.NONE)
        sessions.keys.toList().forEach(::remove)
        selectedId = null
        clearArtwork()
        if (registered) {
            registered = false
            try { manager?.removeOnActiveSessionsChangedListener(sessionListener) } catch (_: RuntimeException) { /* Lost permission. */ }
        }
        if (access != mutableState.value.access) accessEvent(access)
        mutableState.value = MediaState(access = access)
    }
    private fun accessEvent(access: MediaAccess) {
        journal?.record(Component.MEDIA, null, Event.MEDIA_ACCESS, Outcome.OBSERVED,
            when (access) { MediaAccess.GRANTED -> ErrorCode.NONE; MediaAccess.REQUIRED -> ErrorCode.PERMISSION_DENIED; MediaAccess.UNAVAILABLE -> ErrorCode.UNAVAILABLE })
    }
    override fun close() {
        mainThread()
        if (closed) return
        closed = true
        running = false
        scope.cancel()
        clear(MediaAccess.UNAVAILABLE)
        destroyed.clear()
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

internal fun mediaPlayback(state: Int?): MediaPlayback = when (state) {
    null -> MediaPlayback.UNKNOWN
    PlaybackState.STATE_NONE -> MediaPlayback.NONE
    PlaybackState.STATE_STOPPED -> MediaPlayback.STOPPED
    PlaybackState.STATE_PAUSED -> MediaPlayback.PAUSED
    PlaybackState.STATE_PLAYING -> MediaPlayback.PLAYING
    PlaybackState.STATE_BUFFERING -> MediaPlayback.BUFFERING
    PlaybackState.STATE_CONNECTING -> MediaPlayback.CONNECTING
    PlaybackState.STATE_ERROR -> MediaPlayback.ERROR
    else -> MediaPlayback.OTHER
}

internal fun boundedMediaText(value: String): String =
    value.substring(0, value.offsetByCodePoints(0, minOf(512, value.codePointCount(0, value.length))))

internal fun mediaTitle(title: String?, displayTitle: String?): String? =
    (title ?: displayTitle)?.let(::boundedMediaText)

/** The toggle capability alone does not authorize a distinct transportControls.play/pause call. */
internal fun mediaToggleAction(state: Int?): Long? = when (state) {
    PlaybackState.STATE_PLAYING -> PlaybackState.ACTION_PAUSE
    PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED -> PlaybackState.ACTION_PLAY
    else -> null
}
