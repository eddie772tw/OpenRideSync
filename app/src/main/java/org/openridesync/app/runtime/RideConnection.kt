package org.openridesync.app.runtime

import android.app.NotificationManager
import android.content.ComponentName
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.media.session.MediaSession
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.BuildConfig
import org.openridesync.app.R
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.ble.*
import org.openridesync.app.calls.*
import org.openridesync.app.media.MediaAccess
import org.openridesync.app.media.MediaAccessService
import org.openridesync.app.messages.*
import org.openridesync.app.navigation.*
import org.openridesync.app.media.MediaState
import org.openridesync.app.media.MediaPlayback
import org.openridesync.app.media.NativeMedia
import org.openridesync.app.media.FavoriteMusicStarter
import org.openridesync.app.media.FavoriteStartStatus
import org.openridesync.app.media.DispatchResult
import org.openridesync.app.weather.RideWeather
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.PairedVehicleStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class RideStage { IDLE, CONNECTING, RECONNECTING, CONNECTED, STOPPING, FAILED }
internal data class RideState(
    val stage: RideStage = RideStage.IDLE,
    val selectedMac: String? = null,
    val name: String? = null,
    val mode: Gen1Input.Function? = null,
    val rssi: Int? = null,
    val errorCode: String? = null,
    val phase: RuntimePhase? = null,
    val favoriteStart: FavoriteStartStatus = FavoriteStartStatus.NONE,
    val backgroundWeather: Boolean = false,
    val modeRequest: ModeRequestStage = ModeRequestStage.IDLE,
    val backgroundVoice: Boolean = false,
    val drivingError: Boolean? = null,
)

/** App lifetime state; only an explicit foreground UI action starts a non-sticky service. */
internal class RideConnection(private val app: OpenRideSyncApplication) {
    private class Attempt(val id: String, val uid: String, val revision: Long, val mac: String,
        val ticket: VehicleOperationGate.Ticket) {
        var job: Job? = null
        var stopping = false
        val stopSignal = CompletableDeferred<Unit>()
        var notify: ((RideState) -> Unit)? = null
        var media: NativeMedia? = null
        var mediaJob: Job? = null
        var favoriteStarted = false
        var startup = RideStartupState.NEW
        var incoming: IncomingMessages? = null
        var transport: Transport? = null
        var recovery: RideRecovery? = null
        var tripJob: Job? = null
    }
    /** A fresh reference is the epoch: callbacks and cleanup can only use their own transport. */
    private class Transport {
        var closed = false
        @Volatile var rawConnected = false
        var runtimeJob: Job? = null
        var favoriteJob: Job? = null
        var controls: Gen1ControlExchange? = null
        var weather: RideWeather? = null
        var statusReport: RideStatusReport? = null
        var messages: NativeMessages? = null
        var navigation: NativeNavigation? = null
        var callSource: NativeCalls? = null
        var musicPage: RidePages? = null
        var musicJob: Job? = null
        var popups: RidePopups? = null
        var modeSwitch: RideModeSwitch? = null
        var inputMonitor: Gen1InputMonitor? = null
        var displayCapabilityBits: Int? = null
        val startupReady = MutableStateFlow(false)
        var calls: RideCallContext? = null
        val volume = MutableStateFlow<RideVolume.State?>(null)
    }
    private val session = AuthSession.get()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = mutableState.asStateFlow()
    private val mutableMedia = MutableStateFlow<MediaState?>(null)
    val media: StateFlow<MediaState?> = mutableMedia.asStateFlow()
    private val mutableMusicPage = MutableStateFlow(RidePageState())
    val musicPage: StateFlow<RidePageState> = mutableMusicPage.asStateFlow()
    private val mutableCalls = MutableStateFlow(CallContext())
    val calls: StateFlow<CallContext> = mutableCalls.asStateFlow()
    private val mutableNavigation = MutableStateFlow(NavigationObservation())
    val navigation: StateFlow<NavigationObservation> = mutableNavigation.asStateFlow()
    private val mutableCallInbox = MutableStateFlow(CallInboxState())
    val callInbox: StateFlow<CallInboxState> = mutableCallInbox.asStateFlow()
    private val rideVolume = RideVolume(app)
    private var attempt: Attempt? = null
    private var observedUid = session.currentUser()?.uid
    private var observedRevision = session.revision
    private val handlebarRecorder = HandlebarTest()
    val handlebarTest = handlebarRecorder.state
    private var handlebarOwner: Attempt? = null
    private var handlebarObserver: Job? = null

    fun createHandlebarTest(): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        if (!BuildConfig.DEBUG || !active(current, link) || link.inputMonitor == null) return false
        stopHandlebarTest(HandlebarEnd.CANCELLED)
        handlebarOwner = current
        handlebarRecorder.create(current.id)
        return true
    }

    fun startHandlebarStep(gesture: HandlebarGesture): Boolean {
        mainThread()
        val current = handlebarOwner ?: return false
        val link = current.transport ?: return false
        val monitor = link.inputMonitor ?: return false
        if (!BuildConfig.DEBUG || !active(current, link)) return false
        if (!handlebarRecorder.start(gesture, monitor.sequence,
                HandlebarContext(monitor.mode.value.function, monitor.mode.value.revision, musicPage.value.stage))) return false
        val testId = checkNotNull(handlebarTest.value.id)
        handlebarObserver = scope.launch(checkNotNull(current.job), start = CoroutineStart.UNDISPATCHED) {
            monitor.observations.collect { observation ->
                if (handlebarOwner === current && handlebarTest.value.id == testId && active(current, link))
                    handlebarRecorder.accept(testId, current.id, observation, musicPage.value.stage)
            }
        }
        return true
    }

    fun endHandlebarStep(reason: HandlebarEnd = HandlebarEnd.ENDED) {
        mainThread()
        handlebarObserver?.cancel()
        handlebarObserver = null
        handlebarRecorder.end(reason, handlebarOwner?.transport?.inputMonitor?.sequence)
    }

    fun stopHandlebarTest(reason: HandlebarEnd = HandlebarEnd.SCREEN_LEFT) {
        mainThread()
        endHandlebarStep(reason)
        handlebarRecorder.close(reason)
        handlebarOwner = null
    }

    init {
        scope.launch {
            session.state.collect {
                val current = attempt
                if (current != null && !identity(current)) {
                    stopGracefully(false)
                    mutableState.value = RideState(stage = RideStage.STOPPING)
                } else if (current == null && (it.status != AuthStatus.SIGNED_IN ||
                    observedUid != session.currentUser()?.uid || observedRevision != session.revision)) mutableState.value = RideState()
                observedUid = session.currentUser()?.uid
                observedRevision = session.revision
            }
        }
    }

    fun connect(vehicle: PairedVehicleStore.Vehicle) {
        mainThread()
        if (attempt != null) return
        val uid = session.currentUser()?.uid
        if (uid == null || session.state.value.status != AuthStatus.SIGNED_IN) { fail("AUTH_REQUIRED"); return }
        if (permissions().any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            fail("PERMISSION_REQUIRED"); return
        }
        val ticket = app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.RUNTIME)
            ?: run { fail("OPERATION_BUSY"); return }
        val current = Attempt(UUID.randomUUID().toString(), uid, session.revision, vehicle.mac, ticket)
        attempt = current // Reserve synchronously, before the service launch can be delivered.
        mutableState.value = RideState(RideStage.CONNECTING, vehicle.mac, vehicle.vehicleName, phase = RuntimePhase.PREPARING)
        record(current, Event.BLE_RUNTIME_SERVICE, Outcome.STARTED, ErrorCode.NONE)
        try {
            app.startForegroundService(Intent(app, RideService::class.java)
                .setAction(RideService.CONNECT).putExtra(RideService.ATTEMPT, current.id))
        } catch (_: SecurityException) { finish(current, "PERMISSION_REQUIRED") }
        catch (_: RuntimeException) { finish(current, "SERVICE_START_FAILED") }
    }

    fun disconnect() = stopGracefully(true)

    fun requestRoadSyncMode(): Boolean {
        val current = attempt ?: return false
        val link = current.transport ?: return false
        return active(current, link) && link.modeSwitch?.request() == true
    }

    /** Explicit foreground recovery; keeps this vehicle connection and any user-selected player. */
    fun retryFavoriteMusic(): Boolean {
        mainThread()
        return attempt?.let(::launchFavorite) == true
    }

    /** Explicit phone selection only; an exact live token is retained for this connection.
     * Selection updates the current menu but never starts playback. No token leaves this process. */
    fun selectMedia(token: MediaSession.Token): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        if (!active(current, link)) return false
        current.media?.let { retained ->
            if (retained.selectToken(token)) return true
        }
        val replacement = NativeMedia(app, app.journal)
        replacement.start()
        if (!replacement.selectToken(token) || !retainMedia(current, link, replacement)) {
            replacement.close()
            return false
        }
        return true
    }

    /** Transfer the source itself: browser-only tokens require their service binding to stay alive. */
    private fun retainMedia(current: Attempt, link: Transport, replacement: NativeMedia): Boolean {
        if (!active(current, link) || replacement.selectedToken() == null) return false
        releaseMedia(current)
        current.media = replacement
        val observation = scope.launch(checkNotNull(current.job), start = CoroutineStart.LAZY) {
            replacement.state.collect { value ->
                if (attempt !== current || current.media !== replacement) return@collect
                if (current.stopping || !identity(current) || value.access != MediaAccess.GRANTED || value.selectedId == null) {
                    releaseMedia(current)
                } else mutableMedia.value = value
            }
        }
        current.mediaJob = observation
        observation.start()
        return current.media === replacement
    }

    /** Phone UI may select a browser-only candidate already observed by this live ride owner. */
    fun selectMediaChoice(id: String): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        val source = current.media ?: return false
        return active(current, link) && source.select(id) && current.media === source
    }

    fun dispatchMedia(id: String, command: (NativeMedia) -> DispatchResult): DispatchResult {
        mainThread()
        val current = attempt ?: return DispatchResult.STALE
        val link = current.transport ?: return DispatchResult.STALE
        val source = current.media ?: return DispatchResult.STALE
        if (!active(current, link) || source.selectedToken() == null || current.media !== source ||
            source.state.value.selectedId != id) return DispatchResult.STALE
        return command(source)
    }

    fun selectedMediaToken(): MediaSession.Token? {
        mainThread()
        val current = attempt ?: return null
        val link = current.transport ?: return null
        if (!active(current, link)) return null
        val source = current.media ?: return null
        val token = source.selectedToken()
        return token.takeIf { active(current, link) && current.media === source }
    }

    fun openMusicPage(): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        if (!active(current, link) || selectedMediaToken() == null) return false
        return link.musicPage?.open() == true
    }

    fun openFunction(item: MainMenuSelection.Item): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        return active(current, link) && link.musicPage?.openFunction(item) == true
    }

    fun returnToMenu(): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        return active(current, link) && link.musicPage?.returnToMenu() == true
    }

    fun performAction(function: Gen1Input.Function): Boolean {
        mainThread()
        val current = attempt ?: return false
        val link = current.transport ?: return false
        return active(current, link) && link.musicPage?.performAction(function) == true
    }

    private fun releaseMedia(current: Attempt) {
        current.transport?.musicPage?.clearSelection()
        current.transport?.popups?.clearSelection()
        val previous = current.media
        current.media = null
        current.mediaJob?.cancel()
        current.mediaJob = null
        previous?.close()
        if (attempt === current) mutableMedia.value = null
    }

    private fun stopGracefully(userRequested: Boolean) {
        mainThread()
        val current = attempt ?: return
        val link = current.transport
        if (current.stopping && userRequested) return
        stopHandlebarTest(HandlebarEnd.CONNECTION_ENDED)
        val initialized = mutableState.value.stage == RideStage.CONNECTED && link?.runtimeJob?.isActive == true
        current.stopping = true
        current.recovery?.close()
        link?.favoriteJob?.cancel()
        link?.weather?.close()
        link?.statusReport?.close()
        link?.messages?.close()
        if (link != null) {
            releaseNavigation(current, link)
            releaseCalls(current, link)
        }
        link?.musicPage?.close()
        link?.popups?.close()
        releaseMedia(current)
        mutableMusicPage.value = RidePageState()
        mutableState.value = mutableState.value.copy(stage = RideStage.STOPPING, mode = null, rssi = null, errorCode = null, phase = null)
        try { current.notify?.invoke(mutableState.value) } catch (_: RuntimeException) { /* Stop still owns cleanup. */ }
        record(current, Event.BLE_RUNTIME_STOP, Outcome.STARTED, ErrorCode.NONE)
        if (userRequested && initialized) current.stopSignal.complete(Unit)
        else current.job?.cancel()
        // Before service delivery, keep the reservation: the accepted start must promote then stop.
    }

    internal fun accepts(id: String?): Boolean = id != null && attempt?.id == id
    internal fun stop(id: String?) { if (accepts(id)) disconnect() }
    internal fun promoted(id: String, backgroundWeather: Boolean = false, backgroundVoice: Boolean = false) {
        attempt?.takeIf { it.id == id }?.let {
            mutableState.value = mutableState.value.copy(backgroundWeather = backgroundWeather, backgroundVoice = backgroundVoice)
            record(it, Event.BLE_RUNTIME_SERVICE, Outcome.SUCCEEDED, ErrorCode.NONE)
        }
    }

    internal fun foregroundEnded(id: String?) {
        if (accepts(id)) mutableState.value = mutableState.value.copy(backgroundWeather = false, backgroundVoice = false)
    }

    internal fun attach(id: String, serviceScope: CoroutineScope, notify: (RideState) -> Unit,
        done: () -> Unit): Job? {
        mainThread()
        val current = attempt?.takeIf { it.id == id && it.job == null } ?: return null
        current.notify = notify
        var error: String? = null
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                checkCurrent(current)
                val vehicle = withContext(Dispatchers.IO) {
                    val hash = MessageDigest.getInstance("SHA-256").digest(current.uid.toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    PairedVehicleStore(File(app.filesDir, "paired-vehicles/$hash")).read()
                        .singleOrNull { it.mac == current.mac } ?: throw RideFailure("VEHICLE_NOT_FOUND")
                }
                checkCurrent(current)
                mutableState.value = mutableState.value.copy(name = vehicle.vehicleName)
                val generation = rideGeneration(vehicle.state, vehicle.model.meter.generation)
                val journal = app.journal ?: throw RideFailure("DIAGNOSTICS_UNAVAILABLE")
                coroutineScope {
                    val recovery = RideRecovery(this,
                        sample = { recoveryMotion(app, state.value.backgroundWeather) },
                        pending = { reconnecting(current) })
                    current.recovery = recovery
                    try {
                        reconnectRide(onRetry = { failure, _ ->
                            recovery.retrying()
                            reconnecting(current)
                            record(current, Event.RETRY, Outcome.OBSERVED, failure.code)
                        }) {
                            checkCurrent(current)
                            // Native policy: revoked permission, disabled radio or lost bond ends
                            // recovery; manual connection remains available after the user fixes it.
                            val device = checkedDevice(vehicle.mac)
                            check(current.transport == null)
                            val link = Transport().also { current.transport = it }
                            try {
                                Gen1RuntimeConnection.run(app, device, generation, journal, operation = current.id, onSubscribed = { connection ->
                                    checkCurrent(current, link)
                                    link.inputMonitor = connection.monitor
                                    link.controls = Gen1ControlExchange(connection.controlNotifications, connection::writeControl,
                                        journal, connection.operation).also { exchange -> launch { exchange.run() } }
                                    launch {
                                        connection.monitor.mode.collect { mode ->
                                            checkCurrent(current, link)
                                            mutableState.value = mutableState.value.copy(mode = mode.function)
                                        }
                                    }
                                    launch {
                                        connection.rssi.collect { rssi ->
                                            checkCurrent(current, link)
                                            mutableState.value = mutableState.value.copy(rssi = rssi)
                                        }
                                    }
                                    launch {
                                        connection.monitor.driving.collect { info ->
                                            checkCurrent(current, link)
                                            mutableState.value = mutableState.value.copy(drivingError = info?.isError)
                                        }
                                    }
                                }, onProgress = { phase ->
                                    checkCurrent(current, link)
                                    mutableState.value = mutableState.value.copy(phase = phase)
                                }, onRawState = { connected ->
                                    link.rawConnected = connected
                                    if (!current.stopping) recovery.rawState(connected)
                                }) { connection ->
                                    checkCurrent(current, link)
                                    val controls = checkNotNull(link.controls)
                                    link.runtimeJob = coroutineContext[Job]
                                    link.displayCapabilityBits = connection.information.displayCapabilityBits
                                    mutableState.value = mutableState.value.copy(stage = RideStage.CONNECTED, phase = null)
                                    if (current.tripJob == null) {
                                        current.tripJob = scope.launch(checkNotNull(current.job), start = CoroutineStart.LAZY) {
                                            app.tripRecording.recordRide(current.uid, current.revision, current.id,
                                                series = org.openridesync.app.trips.tripSeriesModelCode(vehicle.model, vehicle.seriesCode),
                                                rawSeries = vehicle.seriesCode, name = vehicle.model.modelName.defaultName.fullName,
                                                connectionChanges = state,
                                                isConnected = { current.transport?.let { active(current, it) } == true },
                                                locationForeground = { state.value.backgroundWeather },
                                                ownsAttempt = { attempt === current && identity(current) })
                                        }.also { it.start() }
                                    }
                                    record(current, Event.BLE_RUNTIME_STATE, Outcome.SUCCEEDED, ErrorCode.NONE)
                                    try { notify(mutableState.value) } catch (_: RuntimeException) { /* Existing foreground notification remains. */ }
                                    val calls = RideCallContext(app).also { link.calls = it; it.start() }
                                    val weather = RideWeather(app, app.journal, current.id) {
                                        active(current, link) && link.startupReady.value &&
                                            link.inputMonitor?.mode?.value?.function == Gen1Input.Function.MODE_SAB &&
                                            link.calls?.state?.value?.let { it.phoneObserved && it.cellularIdle && !it.musicPopupBlocked } == true &&
                                            link.musicPage?.weatherReportCurrent() == true
                                    }
                                        .also { link.weather = it }
                                    val statusReport = RideStatusReport(app, app.journal, current.id, isCurrent = {
                                        active(current, link) && link.startupReady.value &&
                                            link.inputMonitor?.mode?.value?.function == Gen1Input.Function.MODE_SAB &&
                                            link.calls?.state?.value?.let { it.phoneObserved && it.cellularIdle && !it.musicPopupBlocked } == true &&
                                            link.musicPage?.statusReportCurrent() == true
                                    }).also { link.statusReport = it }
                                    val inbox = MutableStateFlow(MessageInboxState())
                                    val incoming = current.incoming ?: run {
                                        val recovery = runCatching { MessageRecoveryStore(app, current.uid) }.getOrNull()
                                        val pending = recovery?.load()
                                        if (pending == null)
                                            app.journal?.record(Component.NOTIFICATION, current.id, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.IO)
                                        IncomingMessages(pending) { values ->
                                            val owned = attempt === current && !current.stopping && identity(current)
                                            val saved = owned && recovery?.save(values) == true
                                            if (!saved && owned)
                                                app.journal?.record(Component.NOTIFICATION, current.id, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.IO)
                                            saved
                                        }.also { current.incoming = it }
                                    }
                                    val starting = current.startup
                                    link.startupReady.value = starting == RideStartupState.READY
                                    refreshVolume(current, link)
                                    val quickReplies = MutableStateFlow(quickReplyTexts(app, app.quickReplyPreferences.entries.value))
                                    val page = RidePages(connection.monitor, checkNotNull(controls), media, calls.state,
                                        link.volume, connection.information.displayCapabilityBits,
                                        RidePageLabels(app.getString(R.string.ride_music_unknown), app.getString(R.string.ride_music),
                                            app.getString(R.string.ride_music_playing), app.getString(R.string.ride_music_choose_player),
                                            app.getString(R.string.ride_volume), app.getString(R.string.ride_weather),
                                            app.getString(R.string.ride_weather_loading), app.getString(R.string.ride_weather_unavailable),
                                            app.getString(R.string.ride_messages), app.getString(R.string.ride_messages_empty),
                                            app.getString(R.string.ride_message_reading), app.getString(R.string.ride_message_complete),
                                            app.getString(R.string.ride_message_failed),
                                            navigation = app.getString(R.string.ride_navigation),
                                            navigating = app.getString(R.string.ride_navigating),
                                            navigationWaiting = app.getString(R.string.ride_navigation_waiting),
                                            navigationCancel = app.getString(R.string.ride_navigation_cancel),
                                            navigationCancelUnavailable = app.getString(R.string.ride_navigation_cancel_unavailable),
                                            call = app.getString(R.string.ride_call),
                                            callIncoming = app.getString(R.string.ride_call_incoming),
                                            callOngoing = app.getString(R.string.ride_call_ongoing),
                                            callEndConfirm = app.getString(R.string.ride_call_cancel),
                                            callUnavailable = app.getString(R.string.ride_call_unavailable),
                                            statusReport = app.getString(R.string.ride_status_report),
                                            statusBattery = app.getString(R.string.ride_status_battery),
                                            statusMobile = app.getString(R.string.ride_status_mobile),
                                            statusWifi = app.getString(R.string.ride_status_wifi),
                                            statusLoading = app.getString(R.string.ride_status_loading),
                                            statusSpeaking = app.getString(R.string.ride_status_speaking),
                                            statusUnavailable = app.getString(R.string.ride_status_unavailable)),
                                        app.resources.configuration.locales[0],
                                        app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                                        { active(current, link) }, {
                                            checkCurrent(current, link)
                                            check(current.startup == RideStartupState.NEW)
                                            current.startup = RideStartupState.RUNNING
                                            try {
                                                // No HERE consent adapter exists yet; do not invent or persist SDK consent.
                                                runRideStartupWarning(app, app.journal, current.id, hereConsentNotHandled = false)
                                                checkCurrent(current, link)
                                                current.startup = RideStartupState.READY
                                                link.startupReady.value = true
                                            } finally {
                                                if (current.startup == RideStartupState.RUNNING) current.startup = RideStartupState.FAILED
                                            }
                                        }, { function, selected -> dispatchMusic(current, link, function, selected) },
                                        { function -> dispatchVolume(current, link, function) }, app.ridePreferences.autoReturn,
                                        weather.state, { weather.report() }, weather::cancel,
                                        functionOrder = app.ridePreferences.functionOrder, messages = inbox,
                                        readMessage = { selected -> readMessage(current, link, selected) },
                                        navigation = navigation,
                                        repeatNavigation = { selected -> repeatNavigation(current, link, selected) },
                                        cancelNavigation = { selected -> cancelNavigation(current, link, selected) },
                                        callInbox = callInbox,
                                        dispatchCall = { selected, action -> dispatchRideCall(current, link, selected, action) },
                                        interceptInput = { link.popups?.handleInput(it) == true },
                                        popupBlocksInput = { link.popups?.blocksInput() == true },
                                        messageMode = app.ridePreferences.incomingMessages,
                                        promptMessage = { selected, mode -> promptMessage(current, link, selected, mode) },
                                        replyAvailability = ::replyAvailability,
                                        recognizeReply = { selected -> recognizeMessageReply(current, link, selected) },
                                        confirmReply = { selected, text -> confirmMessageReply(current, link, selected, text) },
                                        dispatchReply = { selected, text -> sendMessageReply(current, link, selected, text) },
                                        reportReplyResult = { selected, result -> reportMessageReply(current, link, selected, result) },
                                        startupState = starting, incoming = incoming,
                                        statusReport = statusReport.state, reportStatus = statusReport::report,
                                        cancelStatus = statusReport::cancel, quickReplies = quickReplies,
                                        quickRepliesCurrent = { retained ->
                                            val latest = quickReplyTexts(app, app.quickReplyPreferences.entries.value)
                                            quickReplies.value = latest
                                            retained == latest
                                        })
                                    link.musicPage = page
                                    val popup = RidePopups(connection.monitor, checkNotNull(controls), media, calls.state,
                                        link.startupReady, connection.information.displayCapabilityBits, { active(current, link) },
                                        callInbox, app.getString(R.string.ride_call_incoming), page.state)
                                    link.popups = popup
                                    val modeSwitch = RideModeSwitch(scope, connection.monitor, checkNotNull(controls)) {
                                        active(current, link) // Control-plane recovery must not depend on business startup prompts.
                                    }
                                    link.modeSwitch = modeSwitch
                                    link.musicJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                        val indicatorMasks = MutableStateFlow(0)
                                        val indicatorInternet = MutableStateFlow<Boolean?>(null)
                                        // Indicator-only meters cannot run the MYSTERY_BOX startup page.
                                        val indicatorReady = if (link.displayCapabilityBits?.let { it and 4 != 0 } == true)
                                            link.startupReady else MutableStateFlow(true)
                                        launch {
                                            runRideIndicators(connection.monitor, controls, link.displayCapabilityBits,
                                                indicatorMasks, indicatorReady) {
                                                active(current, link)
                                            }
                                        }
                                        launch {
                                            combine(media, calls.state, inbox, navigation, link.volume) { player, call, messages, route, volume ->
                                                rideIndicatorMask(
                                                    phone = !call.cellularIdle || call.notificationActive || call.notificationIncoming,
                                                    navigation = route.status == NavigationStatus.OBSERVED,
                                                    music = player?.access == MediaAccess.GRANTED && player.selectedId != null &&
                                                        player.sessions.count { it.id == player.selectedId } == 1 && player.playback == MediaPlayback.PLAYING,
                                                    message = messages.access == MessageAccess.READY && messages.messages.isNotEmpty(),
                                                    muted = volume?.interruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE,
                                                    internet = null)
                                            }.combine(indicatorInternet) { mask, internet ->
                                                mask or (if (internet == false) Gen1ControlPoint.NO_NETWORK else 0)
                                            }.collect { mask -> indicatorMasks.value = mask }
                                        }
                                        launch {
                                            // Share the existing ride lifetime; connectivity failure remains unknown.
                                            while (active(current, link)) {
                                                val internet = runCatching {
                                                    app.getSystemService(ConnectivityManager::class.java)?.let { manager ->
                                                        manager.getNetworkCapabilities(manager.activeNetwork)
                                                            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                                                    }
                                                }.getOrNull()
                                                indicatorInternet.value = internet
                                                delay(5_000)
                                            }
                                        }
                                        launch(start = CoroutineStart.UNDISPATCHED) {
                                            app.quickReplyPreferences.entries.collect {
                                                quickReplies.value = quickReplyTexts(app, it)
                                            }
                                        }
                                        launch { modeSwitch.state.collect {
                                            if (active(current, link)) {
                                                mutableState.value = mutableState.value.copy(modeRequest = it)
                                                val failure = when (it) {
                                                    ModeRequestStage.REJECTED -> ErrorCode.FAILED_PRECONDITION
                                                    ModeRequestStage.TIMED_OUT -> ErrorCode.TIMEOUT
                                                    ModeRequestStage.UNAVAILABLE -> ErrorCode.UNAVAILABLE
                                                    else -> null
                                                }
                                                if (failure != null) record(current, Event.STATE_CHANGE, Outcome.FAILED, failure)
                                            }
                                        } }
                                        launch {
                                            connection.monitor.mode.map {
                                                Build.VERSION.SDK_INT >= 34 && callObservationActive(current, link)
                                            }.distinctUntilChanged().collectLatest { enabled ->
                                                if (enabled) {
                                                    val source = NativeCalls(app, app.journal) { callControlActive(current, link) }
                                                    link.callSource = source
                                                    try {
                                                        source.start()
                                                        source.state.collect {
                                                            if (callObservationActive(current, link) && link.callSource === source)
                                                                mutableCallInbox.value = it
                                                        }
                                                    } finally {
                                                        source.close()
                                                        if (link.callSource === source) releaseCalls(current, link)
                                                    }
                                                }
                                            }
                                        }
                                        launch {
                                            connection.monitor.mode.map { mode ->
                                                active(current, link) && connection.information.displayCapabilityBits?.let { it and 4 != 0 } == true &&
                                                    mode.function == Gen1Input.Function.MODE_SAB
                                            }.distinctUntilChanged().collectLatest { enabled ->
                                                if (enabled) {
                                                    val source = NativeMessages(app, app.journal)
                                                    link.messages = source
                                                    try {
                                                        source.start()
                                                        source.state.collect { inbox.value = it }
                                                    } finally {
                                                        source.close()
                                                        if (link.messages === source) link.messages = null
                                                        inbox.value = MessageInboxState()
                                                    }
                                                }
                                            }
                                        }
                                        launch {
                                            combine(connection.monitor.mode, calls.state) { mode, call ->
                                                active(current, link) && connection.information.displayCapabilityBits?.let { it and 4 != 0 } == true &&
                                                    mode.function == Gen1Input.Function.MODE_SAB && !call.musicPopupBlocked
                                            }.distinctUntilChanged().collectLatest { enabled ->
                                                if (enabled) {
                                                    val source = NativeNavigation(app, app.journal) { navigationActive(current, link) }
                                                    link.navigation = source
                                                    try {
                                                        source.start()
                                                        source.state.collect {
                                                            if (navigationActive(current, link) && link.navigation === source) mutableNavigation.value = it
                                                        }
                                                    } finally {
                                                        source.close()
                                                        if (link.navigation === source) releaseNavigation(current, link)
                                                    }
                                                }
                                            }
                                        }
                                        launch { popup.run() }
                                        launch { page.state.collect { if (active(current, link)) mutableMusicPage.value = it } }
                                        launch { calls.state.collect { if (active(current, link)) mutableCalls.value = it } }
                                        launch {
                                            while (active(current, link)) {
                                                refreshVolume(current, link)
                                                delay(1_000)
                                            }
                                        }
                                        page.run()
                                    }
                                    if (!current.favoriteStarted) {
                                        current.favoriteStarted = true
                                        launchFavorite(current)
                                    }
                                    current.stopSignal.await()
                                    // Only an explicit stop of an initialized live connection reaches this path.
                                    // Auth changes and transport failures cancel it instead.
                                    page.close()
                                    popup.close()
                                    link.musicJob?.join()
                                    // Cancelling an outstanding ATT write closes GATT. Drain it before final clear.
                                    controls.stopGracefully()
                                    currentCoroutineContext().ensureActive()
                                    if (identity(current)) {
                                        val capabilities = connection.information.displayCapabilityBits
                                        if (capabilities != null) try {
                                            val receipts = Gen1Shutdown.exchange(capabilities, checkNotNull(controls)::nextRequestIdForShutdown,
                                                app.getString(R.string.ride_meter_brand), app.getString(R.string.ride_meter_off),
                                                connection.controlNotifications, connection::writeControl)
                                            val accepted = receipts.all { it.status() == 1 }
                                            if (receipts.isNotEmpty()) record(current, Event.BLE_CONTROL_RECEIPT,
                                                if (accepted) Outcome.SUCCEEDED else Outcome.FAILED,
                                                if (accepted) ErrorCode.NONE else ErrorCode.FAILED_PRECONDITION)
                                        } catch (failure: Exception) {
                                            currentCoroutineContext().ensureActive()
                                            val code = when (failure) {
                                                is TimeoutCancellationException -> ErrorCode.TIMEOUT
                                                is GattFailure -> failure.code
                                                is CancellationException -> failure.gattFailureCause()?.code ?: ErrorCode.CANCELLED
                                                else -> ErrorCode.UNKNOWN
                                            }
                                            record(current, Event.BLE_CONTROL_RECEIPT, Outcome.FAILED, code)
                                        }
                                    }
                                }
                            } finally {
                                releaseTransport(current, link)
                            }
                        }
                    } finally {
                        recovery.close()
                        if (current.recovery === recovery) current.recovery = null
                    }
                }
            } catch (failure: CancellationException) {
                // Explicit stop/account change remains cancellation; transport failure stays typed.
                if (!current.stopping && identity(current))
                    error = if (failure is TimeoutCancellationException) "TIMEOUT"
                        else if (currentCoroutineContext().isActive) failure.gattFailureCause()?.code?.name ?: "CANCELLED" else "CANCELLED"
            } catch (failure: Exception) {
                error = when (failure) {
                    is RideFailure -> failure.code
                    is GattFailure -> failure.code.name
                    is SecurityException -> "PERMISSION_REQUIRED"
                    is java.io.IOException -> "STORAGE_FAILED"
                    else -> "UNKNOWN"
                }
            } finally {
                // The recorder removes callbacks and bounds its optional final fix before the
                // service can drop LOCATION foreground capability. Saved samples survive failure.
                withContext(NonCancellable) { current.tripJob?.cancelAndJoin() }
                current.tripJob = null
                // The retained observer is a child of this attempt, not its last transport.
                // Cancel it before completion can join children and release the service gate.
                releaseMedia(current)
            }
        }
        current.job = job
        // Completion runs only after run() and all structured GATT/monitor children finish.
        job.invokeOnCompletion { scope.launch { finish(current, error); done() } }
        return job
    }

    internal fun promotionFailed(id: String?, code: String) {
        val current = attempt?.takeIf { it.id == id && it.job == null } ?: return
        finish(current, code)
    }

    private fun reconnecting(current: Attempt) {
        if (attempt !== current || current.stopping || !identity(current)) return
        mutableState.value = mutableState.value.copy(stage = RideStage.RECONNECTING,
            mode = null, rssi = null, modeRequest = ModeRequestStage.IDLE)
        try { current.notify?.invoke(mutableState.value) } catch (_: RuntimeException) { /* Keep recovery cancellable. */ }
    }

    private fun identity(current: Attempt) = session.state.value.status == AuthStatus.SIGNED_IN &&
        session.revision == current.revision && session.currentUser()?.uid == current.uid
    private fun sameTransport(current: Attempt, link: Transport) =
        attempt === current && current.transport === link && !link.closed

    private fun active(current: Attempt, link: Transport) = sameTransport(current, link) && !current.stopping && identity(current) &&
        link.rawConnected && current.job?.isActive == true && link.runtimeJob?.isActive == true && state.value.stage == RideStage.CONNECTED

    private fun launchFavorite(current: Attempt): Boolean {
        val link = current.transport ?: return false
        // Keep the original inner runtime parent: transport faults cancel acquisition before
        // that scope finishes cleanup and propagates its failure to the outer service job.
        val parent = link.runtimeJob ?: return false
        if (!active(current, link) || current.media != null || link.favoriteJob?.isCompleted == false) return false
        // Assign before starting: Main.immediate may finish inline; cancelled jobs must finish
        // their browser cleanup before another attempt can acquire ownership.
        val job = scope.launch(parent, start = CoroutineStart.LAZY) {
            try { startFavorite(current, link) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuntimeException) {
                if (active(current, link)) mutableState.value = mutableState.value.copy(favoriteStart = FavoriteStartStatus.APP_UNAVAILABLE)
            }
        }
        link.favoriteJob = job
        mutableState.value = mutableState.value.copy(favoriteStart = FavoriteStartStatus.STARTING)
        job.start()
        return true
    }

    private suspend fun startFavorite(current: Attempt, link: Transport) {
        link.calls?.let { calls ->
            withTimeoutOrNull(2_000) { calls.state.first { !it.notificationUnknown || !it.cellularIdle || it.notificationActive || it.notificationIncoming } }
        }
        val result = FavoriteMusicStarter(app, app.journal).start(
            canContinue = { active(current, link) },
            hasSelection = { current.media != null },
            attach = { source -> active(current, link) && current.media == null && retainMedia(current, link, source) },
            detach = { if (active(current, link)) releaseMedia(current) },
            callBlocked = { link.calls?.state?.value?.musicPopupBlocked != false },
        )
        if (active(current, link)) mutableState.value = mutableState.value.copy(favoriteStart = result)
    }

    /** Reading may be explicit or follow the user's AUTO_READ choice; never sends a reply. */
    private suspend fun readMessage(current: Attempt, link: Transport, selected: String) =
        speakMessage(current, link, selected) { listOf(it.title, it.text).filter(String::isNotBlank) }

    private suspend fun promptMessage(current: Attempt, link: Transport, selected: String, mode: IncomingMessageMode) =
        speakMessage(current, link, selected) { message ->
            val resource = when (mode) {
                IncomingMessageMode.ASK_TO_LISTEN -> R.string.ride_message_prompt_ask
                IncomingMessageMode.AUTO_READ -> R.string.ride_message_prompt_auto
                IncomingMessageMode.IGNORE -> throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
            }
            // A conversation label is not falsely described as a verified sender.
            listOf(app.getString(resource, message.appName, message.sender ?: message.title))
        }

    private suspend fun speakMessage(current: Attempt, link: Transport, selected: String, lines: (MessageItem) -> List<String>) {
        val source = link.messages ?: throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        source.refresh()
        val message = source.state.value.messages.singleOrNull { it.id == selected }
            ?: throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        val modeRevision = link.inputMonitor?.mode?.value?.revision
        val notifications = app.getSystemService(NotificationManager::class.java)
        val listener = ComponentName(app, MediaAccessService::class.java)
        fun valid(): Boolean = active(current, link) && link.startupReady.value && link.messages === source &&
            link.inputMonitor?.mode?.value?.function == Gen1Input.Function.MODE_SAB &&
            link.inputMonitor?.mode?.value?.revision == modeRevision &&
            link.calls?.state?.value?.musicPopupBlocked == false &&
            link.musicPage?.messageSpeechCurrent(selected) == true &&
            MediaAccessService.callSummary.value.listenerConnected &&
            source.state.value.access == MessageAccess.READY &&
            source.state.value.messages.singleOrNull { it.id == selected } == message &&
            try { notifications?.isNotificationListenerAccessGranted(listener) == true } catch (_: RuntimeException) { false }
        speakRideLines(app, lines(message), app.journal, current.id, ::valid)
    }

    /** A granted microphone alone does not grant background capture for this existing connection. */
    private fun replyAvailability(): RideReplyAvailability = try {
        when {
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                RideReplyAvailability.MICROPHONE_REQUIRED
            !state.value.backgroundVoice -> RideReplyAvailability.RECONNECT_REQUIRED
            !SpeechRecognizer.isRecognitionAvailable(app) -> RideReplyAvailability.UNAVAILABLE
            app.getSystemService(ConnectivityManager::class.java)?.let { manager ->
                manager.getNetworkCapabilities(manager.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } != true -> RideReplyAvailability.NO_NETWORK
            else -> RideReplyAvailability.READY
        }
    } catch (_: RuntimeException) { RideReplyAvailability.UNAVAILABLE }

    /** The exact source, account, transport, mode epoch and page must survive every callback. */
    private fun replyGuard(current: Attempt, link: Transport, selected: String, result: Boolean = false): () -> Boolean {
        val source = link.messages ?: throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        source.refresh()
        val message = source.state.value.messages.singleOrNull { it.id == selected && (result || it.canReply) }
            ?: throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        val modeRevision = link.inputMonitor?.mode?.value?.revision
        val notifications = app.getSystemService(NotificationManager::class.java)
        val listener = ComponentName(app, MediaAccessService::class.java)
        return {
            active(current, link) && link.startupReady.value && link.messages === source &&
                link.inputMonitor?.mode?.value?.function == Gen1Input.Function.MODE_SAB &&
                link.inputMonitor?.mode?.value?.revision == modeRevision &&
                link.calls?.state?.value?.musicPopupBlocked == false &&
                (if (result) link.musicPage?.messageReplyResultCurrent(selected) else link.musicPage?.messageReplyCurrent(selected)) == true &&
                MediaAccessService.callSummary.value.listenerConnected &&
                source.state.value.access == MessageAccess.READY &&
                source.state.value.messages.singleOrNull { it.id == selected } == message &&
                try { notifications?.isNotificationListenerAccessGranted(listener) == true } catch (_: RuntimeException) { false }
        }
    }

    private suspend fun recognizeMessageReply(current: Attempt, link: Transport, selected: String): String {
        val sourceCurrent = replyGuard(current, link, selected)
        val languages = app.recognitionLanguages
        languages.refreshEnvironment()
        val selection = languages.selection.value
        val environment = languages.environmentRevision
        fun valid(): Boolean {
            languages.refreshEnvironment()
            return sourceCurrent() && replyAvailability() == RideReplyAvailability.READY &&
                languages.selection.value == selection && languages.environmentRevision == environment
        }
        if (!valid()) throw RideRecognitionFailure(RideRecognitionError.INTERRUPTED)
        val languageTag = languages.resolveForRecognition(::valid)
        speakRideLines(app, listOf(app.getString(R.string.ride_message_reply_prompt)), app.journal, current.id, ::valid)
        return recognizeRideMessage(app, app.journal, current.id, languageTag = languageTag, valid = ::valid)
    }

    private suspend fun confirmMessageReply(current: Attempt, link: Transport, selected: String, text: String) {
        val valid = replyGuard(current, link, selected)
        speakRideLines(app, listOf(app.getString(R.string.ride_message_reply_review, text)), app.journal, current.id, valid)
    }

    private fun sendMessageReply(current: Attempt, link: Transport, selected: String, text: String): MessageReplyResult {
        val valid = replyGuard(current, link, selected)
        return link.messages?.replyFromRide(selected, text, valid) ?: MessageReplyResult.UNAVAILABLE
    }

    private suspend fun reportMessageReply(current: Attempt, link: Transport, selected: String, result: MessageReplyResult) {
        val valid = replyGuard(current, link, selected, result = true)
        val resource = if (result == MessageReplyResult.DISPATCHED) R.string.ride_message_reply_dispatched
            else R.string.ride_message_reply_not_sent
        speakRideLines(app, listOf(app.getString(resource)), app.journal, current.id, valid)
    }

    private fun navigationActive(current: Attempt, link: Transport) = active(current, link) &&
        link.inputMonitor?.mode?.value?.function == Gen1Input.Function.MODE_SAB &&
        link.calls?.state?.value?.musicPopupBlocked == false

    /** Physical Select repeats only the instruction owned by this connection and snapshot. */
    private suspend fun repeatNavigation(current: Attempt, link: Transport, selected: String) {
        val source = link.navigation ?: throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        val instruction = source.currentInstruction(selected) ?: throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        fun valid() = navigationActive(current, link) && link.navigation === source && source.isCurrent(selected)
        val lines = listOfNotNull(instruction.nextStep, instruction.instruction)
        if (lines.any { it.isBlank() || it.length > android.speech.tts.TextToSpeech.getMaxSpeechInputLength() })
            throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
        speakRideLines(app, lines, app.journal, current.id, ::valid)
    }

    /** RidePages requires a separate Back -> Select confirmation, fenced to the exact source. */
    private fun cancelNavigation(current: Attempt, link: Transport, selected: String): NavigationCancelResult {
        val source = link.navigation ?: return NavigationCancelResult.UNAVAILABLE
        if (!navigationActive(current, link) || !source.isCurrent(selected)) return NavigationCancelResult.STALE
        return source.cancel(selected)
    }

    private fun releaseNavigation(current: Attempt, link: Transport) {
        val previous = link.navigation
        link.navigation = null
        previous?.close()
        if (sameTransport(current, link)) mutableNavigation.value = NavigationObservation()
    }

    private fun callObservationActive(current: Attempt, link: Transport): Boolean {
        if (!active(current, link)) return false
        val capability = link.displayCapabilityBits ?: return false
        return when (link.inputMonitor?.mode?.value?.function) {
            Gen1Input.Function.MODE_SAB -> capability and 4 != 0
            Gen1Input.Function.MODE_HU -> capability and 2 != 0
            else -> false
        }
    }

    private fun handlebarActive(current: Attempt, link: Transport) = active(current, link) && link.startupReady.value &&
        link.inputMonitor?.mode?.value?.function == Gen1Input.Function.MODE_SAB

    private fun callControlActive(current: Attempt, link: Transport) = callObservationActive(current, link) && handlebarActive(current, link)

    /** Only the current ordinary-page/overlay input owner can reach this physical path. */
    private fun dispatchRideCall(current: Attempt, link: Transport, selected: String, action: CallAction): CallDispatchResult {
        val source = link.callSource ?: return CallDispatchResult.UNAVAILABLE
        if (!callControlActive(current, link)) return CallDispatchResult.STALE
        source.refresh()
        val observed = source.state.value
        if (observed.access != CallAccess.READY || observed.calls.singleOrNull()?.let {
                it.id == selected && action in it.actions
            } != true || link.callSource !== source || !callControlActive(current, link)) return CallDispatchResult.STALE
        return source.dispatch(selected, action)
    }

    private fun releaseCalls(current: Attempt, link: Transport) {
        val previous = link.callSource
        link.callSource = null
        previous?.close()
        if (sameTransport(current, link)) mutableCallInbox.value = CallInboxState()
    }

    private suspend fun dispatchMusic(current: Attempt, link: Transport, function: Gen1Input.Function, selected: String) {
        if (!handlebarActive(current, link)) return
        val source = current.media ?: return
        // Refresh the authoritative token/access before volume as well as MediaController actions.
        if (source.selectedToken() == null || current.media !== source || !handlebarActive(current, link) ||
            source.state.value.selectedId != selected) return
        when (function) {
            Gen1Input.Function.BACK -> source.toggle()
            Gen1Input.Function.SELECT -> source.next()
            Gen1Input.Function.NEXT, Gen1Input.Function.PREVIOUS -> {
                val result = rideVolume.adjust(RideVolume.Stream.MEDIA,
                    if (function == Gen1Input.Function.NEXT) RideVolume.Direction.DOWN else RideVolume.Direction.UP)
                recordVolume(current, result.status)
                refreshVolume(current, link)
            }
            else -> Unit
        }
    }
    private fun refreshVolume(current: Attempt, link: Transport) {
        if (!active(current, link)) return
        val calls = link.calls ?: return
        calls.refresh()
        link.volume.value = calls.state.value.volumeStream?.let { rideVolume.read(it).state }
    }

    private suspend fun dispatchVolume(current: Attempt, link: Transport, function: Gen1Input.Function) {
        if (!handlebarActive(current, link)) return
        refreshVolume(current, link)
        if (!handlebarActive(current, link)) return
        val result = if (function == Gen1Input.Function.VOLUME_MUTE) {
            val observed = rideVolume.read(RideVolume.Stream.MEDIA).state
                ?: run { recordVolume(current, RideVolume.Status.UNAVAILABLE); return }
            rideVolume.setMuted(observed.interruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_NONE).status
        } else {
            val stream = link.calls?.state?.value?.volumeStream
                ?: run { recordVolume(current, RideVolume.Status.UNAVAILABLE); return }
            val direction = when (function) {
                Gen1Input.Function.NEXT, Gen1Input.Function.VOLUME_DOWN -> RideVolume.Direction.DOWN
                Gen1Input.Function.PREVIOUS, Gen1Input.Function.VOLUME_UP -> RideVolume.Direction.UP
                else -> return
            }
            rideVolume.adjust(stream, direction).status
        }
        currentCoroutineContext().ensureActive()
        if (active(current, link)) {
            recordVolume(current, result)
            refreshVolume(current, link)
        }
    }

    private fun recordVolume(current: Attempt, status: RideVolume.Status) {
        val code = when (status) {
            RideVolume.Status.OBSERVED, RideVolume.Status.REQUESTED, RideVolume.Status.AT_BOUND -> ErrorCode.NONE
            RideVolume.Status.PERMISSION_REQUIRED -> ErrorCode.PERMISSION_DENIED
            RideVolume.Status.FIXED_VOLUME -> ErrorCode.FAILED_PRECONDITION
            RideVolume.Status.UNAVAILABLE -> ErrorCode.UNAVAILABLE
        }
        app.journal?.record(Component.MEDIA, current.id, Event.REQUEST,
            if (code == ErrorCode.NONE) Outcome.OBSERVED else Outcome.FAILED, code)
    }

    private suspend fun checkCurrent(current: Attempt) {
        currentCoroutineContext().ensureActive()
        if (attempt !== current || current.stopping || !identity(current)) throw CancellationException()
    }

    private suspend fun checkCurrent(current: Attempt, link: Transport) {
        checkCurrent(current)
        if (!sameTransport(current, link)) throw CancellationException()
    }

    private fun releaseTransport(current: Attempt, link: Transport) {
        if (link.closed) return
        val visible = sameTransport(current, link)
        link.closed = true // Fence callbacks before cancelling or closing their sources.
        link.rawConnected = false
        if (visible && handlebarOwner === current) stopHandlebarTest(HandlebarEnd.CONNECTION_ENDED)
        if (current.transport === link) current.transport = null
        link.favoriteJob?.cancel()
        link.favoriteJob = null
        link.weather?.close()
        link.weather = null
        link.statusReport?.close()
        link.statusReport = null
        link.messages?.close()
        link.messages = null
        releaseNavigation(current, link)
        releaseCalls(current, link)
        link.musicPage?.close()
        link.musicPage = null
        link.musicJob = null
        link.popups?.close()
        link.popups = null
        link.modeSwitch?.close()
        link.modeSwitch = null
        link.calls?.close()
        link.calls = null
        link.controls?.close()
        link.controls = null
        link.inputMonitor = null
        link.runtimeJob = null
        link.displayCapabilityBits = null
        link.startupReady.value = false
        link.volume.value = null
        if (visible) {
            mutableMusicPage.value = RidePageState()
            mutableCalls.value = CallContext()
            mutableNavigation.value = NavigationObservation()
            mutableCallInbox.value = CallInboxState()
            mutableState.value = mutableState.value.copy(
                stage = if (state.value.stage == RideStage.CONNECTED) RideStage.STOPPING else state.value.stage,
                mode = null, rssi = null, drivingError = null, modeRequest = ModeRequestStage.IDLE,
                favoriteStart = if (state.value.favoriteStart == FavoriteStartStatus.STARTING)
                    FavoriteStartStatus.INTERRUPTED else state.value.favoriteStart)
        }
    }

    private fun finish(current: Attempt, error: String?) {
        if (attempt !== current) return
        if (handlebarOwner === current) stopHandlebarTest(HandlebarEnd.CONNECTION_ENDED)
        current.recovery?.close()
        current.recovery = null
        current.transport?.let { releaseTransport(current, it) }
        releaseMedia(current)
        mutableMusicPage.value = RidePageState()
        mutableCalls.value = CallContext()
        mutableNavigation.value = NavigationObservation()
        mutableCallInbox.value = CallInboxState()
        attempt = null
        app.vehicleOperations.release(current.ticket)
        current.notify = null
        record(current, if (current.stopping) Event.BLE_RUNTIME_STOP else Event.BLE_RUNTIME_SERVICE,
            if (error == "CANCELLED") Outcome.CANCELLED else if (error == null) Outcome.SUCCEEDED else Outcome.FAILED,
            when (error) {
                null -> ErrorCode.NONE
                "PERMISSION_REQUIRED" -> ErrorCode.PERMISSION_DENIED
                "STORAGE_FAILED" -> ErrorCode.IO
                "RECONNECT_LOCATION_REQUIRED" -> ErrorCode.PERMISSION_DENIED
                "RECONNECT_WINDOW_EXPIRED" -> ErrorCode.TIMEOUT
                "RECONNECT_LIMIT" -> ErrorCode.RESOURCE_EXHAUSTED
                "RECONNECT_STOPPED", "BOND_REQUIRED", "BLUETOOTH_DISABLED" -> ErrorCode.FAILED_PRECONDITION
                "AUTH_REQUIRED" -> ErrorCode.UNAUTHENTICATED
                "VEHICLE_NOT_FOUND" -> ErrorCode.NOT_FOUND
                "SERVICE_START_FAILED", "DIAGNOSTICS_UNAVAILABLE" -> ErrorCode.UNAVAILABLE
                else -> ErrorCode.values().firstOrNull { it.name == error } ?: ErrorCode.UNKNOWN
            })
        mutableState.value = if (!identity(current) || current.stopping || error == null || error == "CANCELLED") RideState()
            else RideState(RideStage.FAILED, current.mac, mutableState.value.name, errorCode = error, phase = mutableState.value.phase)
    }
    private fun fail(code: String) {
        mutableState.value = RideState(RideStage.FAILED, errorCode = code)
        val error = when (code) {
            "AUTH_REQUIRED" -> ErrorCode.UNAUTHENTICATED
            "PERMISSION_REQUIRED" -> ErrorCode.PERMISSION_DENIED
            "OPERATION_BUSY" -> ErrorCode.ALREADY_EXISTS
            else -> ErrorCode.UNKNOWN
        }
        app.journal?.record(Component.BLE, app.journal?.newOperation(), Event.BLE_RUNTIME_STATE, Outcome.FAILED, error)
    }
    private fun record(current: Attempt, event: Event, outcome: Outcome, error: ErrorCode) {
        app.journal?.record(Component.BLE, current.id, event, outcome, error)
    }

    @SuppressLint("MissingPermission")
    private fun checkedDevice(mac: String): BluetoothDevice {
        if (permissions().any { app.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            throw RideFailure("PERMISSION_REQUIRED")
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: throw RideFailure("UNSUPPORTED")
        if (!adapter.isEnabled) throw RideFailure("BLUETOOTH_DISABLED")
        val device = adapter.getRemoteDevice(mac)
        if (device.bondState != BluetoothDevice.BOND_BONDED) throw RideFailure("BOND_REQUIRED")
        return device
    }
    companion object {
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    }
}

internal class RideFailure(val code: String) : Exception(code)

/** Connection does not advance onboarding, repair or update-required garage states. */
internal fun rideGeneration(state: PairedVehicleStore.State, generation: Int): Int {
    if (state !in setOf(PairedVehicleStore.State.PAIRED, PairedVehicleStore.State.READY)) throw RideFailure("INVALID_STATE")
    if (generation !in 1..2) throw RideFailure("UNSUPPORTED")
    return generation
}
