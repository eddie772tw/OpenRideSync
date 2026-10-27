package org.openridesync.app.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.openridesync.app.ble.Gen1ControlExchange
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.ble.Gen1InputMonitor
import org.openridesync.app.ble.Gen1Projection
import org.openridesync.app.calls.*
import org.openridesync.app.media.MediaAccess
import org.openridesync.app.media.MediaState
import org.openridesync.app.media.MusicProjection
import org.openridesync.app.messages.MessageAccess
import org.openridesync.app.messages.MessageInboxState
import org.openridesync.app.messages.MessageProjection
import org.openridesync.app.messages.IncomingMessages
import org.openridesync.app.messages.MessageItem
import org.openridesync.app.messages.MessageReplyResult
import org.openridesync.app.navigation.NavigationCancelResult
import org.openridesync.app.navigation.NavigationObservation
import org.openridesync.app.navigation.NavigationProjection
import org.openridesync.app.navigation.NavigationStatus
import java.util.Locale
import org.openridesync.app.weather.*

internal enum class RidePageStage { DISABLED, SPLASH, STARTUP_FAILED, UNSUPPORTED, NO_SESSION, MENU, MUSIC, VOLUME, WEATHER, STATUS_MENU, STATUS_REPORT, MESSAGE, MESSAGE_PROMPT, MESSAGE_READ, MESSAGE_CATEGORY, MESSAGE_QUICK_LIST, MESSAGE_LISTENING, MESSAGE_CONFIRMATION, MESSAGE_TRY_AGAIN, MESSAGE_SENDING, MESSAGE_SENT, MESSAGE_NOT_SENT, NAVIGATION, NAVIGATION_CANCEL, CALL, CALL_CANCEL }
internal enum class MessageReadStatus { NONE, READING, COMPLETE, FAILED }
internal enum class RideStartupState { NEW, RUNNING, READY, FAILED }
internal data class RidePageState(val stage: RidePageStage = RidePageStage.DISABLED,
    val pending: Boolean = false, val receiptStatus: Int? = null, val volumeAvailable: Boolean = true,
    val messageReadStatus: MessageReadStatus = MessageReadStatus.NONE,
    val navigationCancelResult: NavigationCancelResult? = null,
    val callDispatchResult: CallDispatchResult? = null,
    val messageRecoveryAvailable: Boolean = true, val messageLabel: String? = null,
    val messageCanRead: Boolean = false,
    val messageCanReply: Boolean = false, val replyText: String? = null,
    val replyChoices: List<String> = emptyList(), val replyChoice: Int = 0,
    val replyError: RideReplyAvailability? = null, val replyResult: MessageReplyResult? = null,
    val menuItems: List<MainMenuSelection.Item> = emptyList(),
    val menuSelected: MainMenuSelection.Item? = null,
    val volumePercentage: Int? = null, val weather: RideWeatherState? = null,
    val weatherCanRetry: Boolean = false, val statusChoice: Int = 0,
    val statusReport: RideStatusReportState? = null, val statusCanReport: Boolean = false)
internal data class RidePageLabels(val unknown: String, val music: String, val playing: String,
    val choosePlayer: String, val volume: String, val weather: String = "Weather forecast",
    val weatherLoading: String = "Fetching weather", val weatherUnavailable: String = "Weather unavailable",
    val messages: String = "Messages", val noMessages: String = "No messages",
    val messageReading: String = "Reading message", val messageComplete: String = "Message read",
    val messageFailed: String = "Message unavailable",
    val navigation: String = "Navigation", val navigating: String = "Navigating",
    val navigationWaiting: String = "Start navigation on phone",
    val navigationCancel: String = "End navigation?",
    val navigationCancelUnavailable: String = "End request unavailable",
    val call: String = "Calls", val callIncoming: String = "Incoming call",
    val callOngoing: String = "Call in progress", val callEndConfirm: String = "End call?",
    val callUnavailable: String = "Call action unavailable", val messageReply: String = "Reply",
    val statusReport: String = "Status report", val statusBattery: String = "Phone battery",
    val statusMobile: String = "Mobile network", val statusWifi: String = "Wi-Fi",
    val statusLoading: String = "Reading status", val statusSpeaking: String = "Speaking status",
    val statusUnavailable: String = "Status unavailable")

/** One Main-thread page owner; only implemented providers are visible. No mode request or autoplay. */
internal class RidePages(
    private val monitor: Gen1InputMonitor,
    private val controls: Gen1ControlExchange,
    private val media: StateFlow<MediaState?>,
    private val calls: StateFlow<CallContext>,
    private val volume: StateFlow<RideVolume.State?>,
    private val capabilities: Int?,
    private val labels: RidePageLabels,
    private val locale: Locale,
    coarseLocationGranted: Boolean,
    private val isCurrent: () -> Boolean,
    private val startup: suspend () -> Unit,
    private val dispatchMusic: suspend (Function, String) -> Unit,
    private val dispatchVolume: suspend (Function) -> Unit,
    private val autoReturn: StateFlow<Boolean>,
    private val weather: StateFlow<RideWeatherState>? = null,
    private val reportWeather: suspend () -> Unit = {},
    private val cancelWeather: () -> Unit = {},
    private val functionOrder: StateFlow<List<MainMenuSelection.Item>> = MutableStateFlow(MainMenuSelection.Item.entries),
    private val messages: StateFlow<MessageInboxState>? = null,
    private val readMessage: suspend (String) -> Unit = {},
    private val navigation: StateFlow<NavigationObservation>? = null,
    private val repeatNavigation: suspend (String) -> Unit = {},
    private val cancelNavigation: suspend (String) -> NavigationCancelResult = { NavigationCancelResult.UNAVAILABLE },
    private val callInbox: StateFlow<CallInboxState>? = null,
    private val dispatchCall: suspend (String, CallAction) -> CallDispatchResult = { _, _ -> CallDispatchResult.UNAVAILABLE },
    private val interceptInput: (Gen1InputMonitor.Input) -> Boolean = { false },
    private val popupBlocksInput: () -> Boolean = { false },
    private val messageMode: StateFlow<IncomingMessageMode> = MutableStateFlow(IncomingMessageMode.IGNORE),
    initialPendingMessages: Set<String>? = emptySet(),
    savePendingMessages: (Set<String>) -> Boolean = { true },
    private val promptMessage: suspend (String, IncomingMessageMode) -> Unit = { _, _ -> },
    private val startupState: RideStartupState = RideStartupState.NEW,
    private val incoming: IncomingMessages = IncomingMessages(initialPendingMessages, savePendingMessages),
    private val replyAvailability: () -> RideReplyAvailability = { RideReplyAvailability.UNAVAILABLE },
    private val recognizeReply: suspend (String) -> String = { throw IllegalStateException("Recognition unavailable") },
    private val confirmReply: suspend (String, String) -> Unit = { _, _ -> },
    private val dispatchReply: suspend (String, String) -> MessageReplyResult = { _, _ -> MessageReplyResult.UNAVAILABLE },
    private val reportReplyResult: suspend (String, MessageReplyResult) -> Unit = { _, _ -> },
    private val statusReport: StateFlow<RideStatusReportState>? = null,
    private val reportStatus: suspend (RideStatusKind) -> Unit = {},
    private val cancelStatus: () -> Unit = {},
    private val quickReplies: StateFlow<List<String>> = MutableStateFlow(emptyList()),
    private val quickRepliesCurrent: (List<String>) -> Boolean = { it == quickReplies.value },
) : AutoCloseable {
    private data class Route(val stage: RidePageStage = RidePageStage.DISABLED, val selectedId: String? = null,
        val fence: Long = 0, val revision: Long = 0, val readStatus: MessageReadStatus = MessageReadStatus.NONE,
        val cancelResult: NavigationCancelResult? = null, val callItem: CallItem? = null,
        val callResult: CallDispatchResult? = null, val weatherInterrupted: Boolean = false,
        val replySource: MessageItem? = null, val replyIncoming: Boolean = false, val replyModeRevision: Long = 0,
        val replyText: String? = null, val replyChoice: Int = 0,
        val replyQuickChoices: List<String>? = null,
        val replyError: RideReplyAvailability? = null, val replyResult: MessageReplyResult? = null,
        val statusChoice: Int = 0, val statusOwner: Long = 0, val statusModeRevision: Long = 0,
        val statusState: RideStatusReportState? = null)
    private data class Display(val stage: RidePageStage, val page: Gen1Projection.Page?,
        val revision: Long, val modeRevision: Long, val volumeAvailable: Boolean,
        val readStatus: MessageReadStatus, val cancelResult: NavigationCancelResult?,
        val callResult: CallDispatchResult?, val messageRecoveryAvailable: Boolean, val messageLabel: String?,
        val messageCanRead: Boolean, val messageCanReply: Boolean,
        val replyText: String?, val replyChoices: List<String>, val replyChoice: Int,
        val replyError: RideReplyAvailability?, val replyResult: MessageReplyResult?,
        val statusChoice: Int, val statusState: RideStatusReportState?, val statusCanReport: Boolean)
    private val statusStages = setOf(RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT, RidePageStage.WEATHER)
    private val incomingStages = setOf(RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ)
    private val replyStages = setOf(RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING,
        RidePageStage.MESSAGE_CONFIRMATION, RidePageStage.MESSAGE_TRY_AGAIN, RidePageStage.MESSAGE_SENDING,
        RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT)
    private val actionStages = statusStages + replyStages + setOf(RidePageStage.MENU, RidePageStage.MUSIC, RidePageStage.VOLUME,
        RidePageStage.NO_SESSION, RidePageStage.WEATHER, RidePageStage.MESSAGE,
        RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
        RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL, RidePageStage.CALL, RidePageStage.CALL_CANCEL)
    private var messageDefault: Route? = null
    private var callDefault: Route? = null
    private var incomingMode = messageMode.value
    private val selection = MainMenuSelection(if (coarseLocationGranted) 1 else 2)
    private var menuItems = emptyList<MainMenuSelection.Item>()
    private val route = MutableStateFlow(Route())
    private val mutableState = MutableStateFlow(RidePageState())
    val state: StateFlow<RidePageState> = mutableState.asStateFlow()
    private var owner: CoroutineScope? = null
    private var worker: Job? = null
    private var startupJob: Job? = null
    private var actionJob: Job? = null
    private var messageJob: Job? = null
    private var replyJob: Job? = null
    private var navigationJob: Job? = null
    private var repeatJob: Job? = null
    private var callJob: Job? = null
    private var incomingEpisode = false
    private var weatherJob: Job? = null
    private var statusJob: Job? = null
    private var timeoutJob: Job? = null
    private var pending: Deferred<*>? = null
    private var closed = false

    private fun phoneMenuCurrent() = owner != null && worker?.isActive == true && !closed && isCurrent() &&
        supported() && monitor.mode.value.function == Function.MODE_SAB && !popupBlocksInput()
    private fun visibleMenuItems() = menuItems.takeIf { !closed && isCurrent() && supported() }.orEmpty()
    private fun focusedMenuItem() = selection.selected?.item.takeIf { !closed && isCurrent() && supported() }
    private fun phoneDataCurrent() = !closed && isCurrent() && supported() && monitor.mode.value.function == Function.MODE_SAB
    private fun phoneVolumePercentage() = volume.value?.takeIf {
        phoneDataCurrent() && calls.value.volumeStream == it.stream
    }?.percentage
    private fun phoneWeather() = weather?.value?.takeIf { phoneDataCurrent() && route.value.stage == RidePageStage.WEATHER && statusGate() }
    private fun phoneStatusReport() = route.value.statusState.takeIf {
        phoneDataCurrent() && route.value.stage == RidePageStage.STATUS_REPORT && statusGate() }
    private fun phoneStatusChoice() = route.value.statusChoice.takeIf { phoneDataCurrent() && route.value.stage in statusStages } ?: 0
    private fun phoneStatusCanReport() = phoneDataCurrent() && statusGate() && when (route.value.stage) {
        RidePageStage.STATUS_MENU -> if (route.value.statusChoice == 0) weather != null else statusReport != null
        RidePageStage.STATUS_REPORT -> statusReport != null && route.value.statusState?.stage == RideStatusReportStage.FAILED
        RidePageStage.WEATHER -> weather != null && phoneWeatherCanRetry()
        else -> false
    }
    private fun phoneWeatherCanRetry() = phoneDataCurrent() && statusGate() && route.value.stage == RidePageStage.WEATHER &&
        (route.value.weatherInterrupted || weather?.value?.stage == RideWeatherStage.FAILED)

    /** Same business action as a handlebar key; never manufactures a vehicle input or mode. */
    fun performAction(function: Function): Boolean {
        if (function !in setOf(Function.SELECT, Function.BACK, Function.MENU, Function.NEXT, Function.PREVIOUS,
                Function.VOLUME_UP, Function.VOLUME_DOWN, Function.VOLUME_MUTE) || !phoneMenuCurrent()) return false
        callInbox?.value?.let(::syncCall)
        syncIncoming()
        if (syncQuickReplies()) return false // A phone tap on the previous preference view cannot send its replacement.
        updateSelection()
        val current = route.value
        if (!phoneMenuCurrent() || current.stage !in actionStages) return false
        val scope = owner ?: return false
        scope.handleAction(function, current, monitor.mode.value.revision)
        return true
    }

    /** Explicit phone navigation shares the real menu's providers and entry behavior. */
    fun openFunction(item: MainMenuSelection.Item): Boolean {
        if (!phoneMenuCurrent() || route.value.stage != RidePageStage.MENU) return false
        updateSelection()
        if (item !in menuItems || !canOpenFunction(item)) return false
        selection.resume(item)
        return enterFunction(item)
    }

    /** Incoming-message and call-confirmation ownership must end through their own handlers. */
    fun returnToMenu(): Boolean {
        if (!phoneMenuCurrent() || route.value.stage !in setOf(RidePageStage.MUSIC, RidePageStage.VOLUME,
                RidePageStage.NO_SESSION, RidePageStage.WEATHER, RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT, RidePageStage.MESSAGE, RidePageStage.NAVIGATION)) return false
        navigate(RidePageStage.MENU)
        return true
    }

    private fun canOpenFunction(item: MainMenuSelection.Item): Boolean = when (item) {
        MainMenuSelection.Item.WEATHER -> (weather != null || statusReport != null) && !calls.value.musicBlocked
        MainMenuSelection.Item.MUSIC -> !calls.value.musicBlocked
        MainMenuSelection.Item.MESSAGE -> messagesReady()
        MainMenuSelection.Item.NAVIGATION -> navigationMenuReady()
        MainMenuSelection.Item.CALL -> callSource() != null
        MainMenuSelection.Item.VOLUME -> true
    }

    private fun enterFunction(item: MainMenuSelection.Item): Boolean {
        if (!canOpenFunction(item)) return false
        when (item) {
            MainMenuSelection.Item.WEATHER -> navigate(RidePageStage.STATUS_MENU)
            MainMenuSelection.Item.MUSIC -> {
                val id = selectedId()
                navigate(if (id == null) RidePageStage.NO_SESSION else RidePageStage.MUSIC, id)
            }
            MainMenuSelection.Item.MESSAGE -> navigate(RidePageStage.MESSAGE, messages?.value?.messages?.firstOrNull()?.id)
            MainMenuSelection.Item.NAVIGATION -> navigate(RidePageStage.NAVIGATION, navigationSource()?.sourceId)
            MainMenuSelection.Item.CALL -> callSource()?.let { navigate(RidePageStage.CALL, it.id, it) }
            MainMenuSelection.Item.VOLUME -> navigate(RidePageStage.VOLUME)
        }
        return true
    }

    fun open(): Boolean {
        owner ?: return false
        if (closed || !isCurrent() || !supported()) return false
        if (route.value.stage !in setOf(RidePageStage.MENU, RidePageStage.MUSIC, RidePageStage.VOLUME, RidePageStage.NO_SESSION) ||
            calls.value.musicBlocked) return false
        val id = selectedId() ?: return false
        selection.resume(MainMenuSelection.Item.MUSIC)
        navigate(RidePageStage.MUSIC, id)
        return true
    }

    /** Fence before replacing/closing the controller, including a same-ID replacement. */
    fun clearSelection() {
        if (route.value.stage in statusStages || route.value.stage in incomingStages || route.value.stage in replyStages || route.value.stage in setOf(RidePageStage.MESSAGE, RidePageStage.NAVIGATION,
                RidePageStage.NAVIGATION_CANCEL, RidePageStage.CALL, RidePageStage.CALL_CANCEL)) return // Media selection owns none of these pages.
        actionJob?.cancel()
        val stage = if (route.value.stage == RidePageStage.MUSIC) RidePageStage.MENU else route.value.stage
        navigate(stage)
    }

    private fun navigate(stage: RidePageStage, selectedId: String? = null, callItem: CallItem? = null) {
        if (route.value.stage == RidePageStage.WEATHER || weatherJob != null) stopWeather()
        if (route.value.stage == RidePageStage.STATUS_REPORT || statusJob != null) stopStatus()
        actionJob?.cancel()
        messageJob?.cancel()
        replyJob?.cancel(); replyJob = null
        navigationJob?.cancel()
        repeatJob?.cancel()
        callJob?.cancel()
        pending?.cancel()
        route.value = Route(stage, selectedId, monitor.sequence, route.value.revision + 1,
            callItem = callItem)
        restartTimeout()
    }

    private fun stopWeather() {
        weatherJob?.cancel()
        weatherJob = null
        cancelWeather()
    }

    private fun statusGate() = !closed && isCurrent() && supported() &&
        monitor.mode.value.function == Function.MODE_SAB && calls.value.phoneObserved && calls.value.cellularIdle &&
        !calls.value.musicPopupBlocked && !calls.value.musicBlocked && callSource() == null && !popupBlocksInput()
    private fun statusKind(choice: Int) = when (choice) {
        1 -> RideStatusKind.BATTERY
        2 -> RideStatusKind.MOBILE
        3 -> RideStatusKind.WIFI
        else -> null
    }
    private fun statusLabel(choice: Int) = when (choice) {
        1 -> labels.statusBattery
        2 -> labels.statusMobile
        3 -> labels.statusWifi
        else -> labels.weather
    }
    private fun statusAttemptCurrent(value: Route) = statusGate() && route.value.stage == value.stage &&
        route.value.statusOwner == value.statusOwner && value.statusModeRevision == monitor.mode.value.revision

    fun weatherReportCurrent(): Boolean = route.value.let {
        it.stage == RidePageStage.WEATHER && weatherJob?.isActive == true && statusAttemptCurrent(it)
    }
    fun statusReportCurrent(kind: RideStatusKind? = null): Boolean = route.value.let {
        it.stage == RidePageStage.STATUS_REPORT && statusJob?.isActive == true && statusAttemptCurrent(it) &&
            (kind == null || statusKind(it.statusChoice) == kind)
    }
    private fun statusMenu(choice: Int = route.value.statusChoice) {
        navigate(RidePageStage.STATUS_MENU)
        route.value = route.value.copy(statusChoice = choice.coerceIn(0, 3))
        restartTimeout()
    }
    private fun statusDefault(value: Route) = if (value.stage in statusStages)
        Route(RidePageStage.STATUS_MENU, statusChoice = value.statusChoice) else value
    private fun stopStatus() {
        statusJob?.cancel(); statusJob = null
        cancelStatus()
    }
    private fun startWeather() {
        val source = weather ?: return
        if (weatherJob?.isActive == true || !statusGate()) return
        val choice = route.value.statusChoice
        navigate(RidePageStage.WEATHER)
        route.value = route.value.copy(statusChoice = choice, statusOwner = route.value.revision,
            statusModeRevision = monitor.mode.value.revision)
        val requested = route.value
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            try {
                if (statusAttemptCurrent(requested)) reportWeather()
                if (statusAttemptCurrent(requested)) {
                    if (source.value.stage == RideWeatherStage.COMPLETE) statusMenu(choice)
                    else route.value = route.value.copy(weatherInterrupted = source.value.stage != RideWeatherStage.FAILED,
                        revision = route.value.revision + 1, fence = monitor.sequence)
                }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive && statusAttemptCurrent(requested)) statusMenu(choice)
                throw cancelled
            }
            catch (_: Exception) {
                if (statusAttemptCurrent(requested)) route.value = route.value.copy(weatherInterrupted = true,
                    revision = route.value.revision + 1, fence = monitor.sequence)
            } finally {
                if (weatherJob === currentCoroutineContext()[Job]) {
                    weatherJob = null
                    if (!closed && route.value.stage == requested.stage && route.value.statusOwner == requested.statusOwner && !statusGate())
                        statusMenu(requested.statusChoice)
                }
            }
        } ?: return
        weatherJob = job; job.start()
    }
    private fun startStatus() {
        val kind = statusKind(route.value.statusChoice) ?: return
        val source = statusReport ?: return
        if (statusJob?.isActive == true || !statusGate()) return
        val choice = route.value.statusChoice
        navigate(RidePageStage.STATUS_REPORT)
        route.value = route.value.copy(statusChoice = choice, statusOwner = route.value.revision,
            statusModeRevision = monitor.mode.value.revision,
            statusState = RideStatusReportState(kind, RideStatusReportStage.LOADING))
        val requested = route.value
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            try {
                if (!statusAttemptCurrent(requested)) return@launch
                reportStatus(kind)
                if (statusAttemptCurrent(requested)) {
                    val result = source.value.takeIf { it.kind == kind }
                    if (result?.stage == RideStatusReportStage.COMPLETE) statusMenu(choice)
                    else route.value = route.value.copy(statusState = result?.takeIf { it.stage == RideStatusReportStage.FAILED }
                        ?: RideStatusReportState(kind, RideStatusReportStage.FAILED),
                        revision = route.value.revision + 1, fence = monitor.sequence)
                }
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive && statusAttemptCurrent(requested)) statusMenu(choice)
                throw cancelled
            }
            catch (_: Exception) {
                if (statusAttemptCurrent(requested)) route.value = route.value.copy(
                    statusState = RideStatusReportState(kind, RideStatusReportStage.FAILED),
                    revision = route.value.revision + 1, fence = monitor.sequence)
            } finally {
                if (statusJob === currentCoroutineContext()[Job]) {
                    statusJob = null
                    if (!closed && route.value.stage == requested.stage && route.value.statusOwner == requested.statusOwner && !statusGate())
                        statusMenu(choice)
                }
            }
        } ?: return
        statusJob = job; job.start()
    }

    private fun restartTimeout() {
        timeoutJob?.cancel()
        val stage = route.value.stage
        if (stage in replyStages) {
            if (!autoReturn.value || stage !in setOf(RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_CONFIRMATION) ||
                stage == RidePageStage.MESSAGE_CONFIRMATION && replyJob?.isActive == true) return
            val requested = route.value
            timeoutJob = owner?.launch {
                delay(15_000)
                if (replyOwnerCurrent(requested)) {
                    if (stage != RidePageStage.MESSAGE_CONFIRMATION) exitReply(forget = true, home = true)
                    else {
                        if (requested.replyIncoming) incoming.yield(forget = false)
                        exitReply(forget = false)
                    }
                }
            }
            return
        }
        if (stage in statusStages && (stage != RidePageStage.STATUS_MENU || !autoReturn.value)) return
        if (stage == RidePageStage.NAVIGATION && repeatJob?.isActive == true) return
        if (stage in incomingStages && (!autoReturn.value || route.value.readStatus !in
                setOf(MessageReadStatus.COMPLETE, MessageReadStatus.FAILED))) return
        if (closed || !supported() || (stage != RidePageStage.VOLUME &&
            !(autoReturn.value && stage in setOf(RidePageStage.MUSIC, RidePageStage.NO_SESSION,
                RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL, RidePageStage.STATUS_MENU) ||
                autoReturn.value && stage in incomingStages))) return
        // Navigation repeat pauses this timer while speech is active.
        // Metadata, mode, volume polls, receipts and selection repair never restart this job.
        timeoutJob = owner?.launch {
            delay(15_000)
            if (!closed && isCurrent() && route.value.stage == stage) {
                if (stage in incomingStages) { incoming.yield(forget = false); syncIncoming() }
                else navigate(RidePageStage.MENU)
            }
        }
    }

    private fun supported() = capabilities != null && capabilities and 4 != 0
    private fun selectedId(): String? = media.value?.takeIf { value -> value.access == MediaAccess.GRANTED &&
        value.selectedId != null && value.sessions.count { it.id == value.selectedId } == 1 }?.selectedId
    private fun messagesReady() = monitor.mode.value.function == Function.MODE_SAB && !calls.value.musicPopupBlocked &&
        messages?.value?.access == MessageAccess.READY
    private fun navigationGate() = monitor.mode.value.function == Function.MODE_SAB && !calls.value.musicPopupBlocked
    private fun navigationMenuReady() = navigationGate() && navigation?.value?.status in
        setOf(NavigationStatus.LOADING, NavigationStatus.WAITING, NavigationStatus.OBSERVED)
    private fun navigationPageReady() = navigationGate() && navigation?.value?.status in
        setOf(NavigationStatus.LOADING, NavigationStatus.WAITING, NavigationStatus.OBSERVED)
    private fun navigationSource() = navigation?.value?.takeIf {
        it.status == NavigationStatus.OBSERVED && it.sourceId != null && it.instruction != null
    }
    private fun selectedMessage() = messages?.value?.messages?.singleOrNull { it.id == route.value.selectedId }
    private fun incomingCurrent() = incomingMode == messageMode.value &&
        incoming.front?.mode == messageMode.value && callSource() == null && messagesReady()

    /** Consult live sources before their collectors can resume; manual reads remain explicit. */
    fun messageSpeechCurrent(id: String): Boolean = !closed && isCurrent() && messagesReady() &&
        callSource() == null && route.value.selectedId == id && selectedMessage() != null &&
        (route.value.stage == RidePageStage.MESSAGE ||
            route.value.stage in incomingStages && incomingCurrent() && incoming.front?.item?.id == id)
    private val replyOutcomeStages = setOf(RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT)

    /** The retained source and input mode own a draft; display text is never an action target. */
    private fun replyOwnerCurrent(value: Route, requireAction: Boolean = true): Boolean {
        val source = value.replySource ?: return false
        val live = messages?.value?.messages?.singleOrNull { it.id == source.id } ?: return false
        return !closed && isCurrent() && supported() && messagesReady() && callSource() == null &&
            !popupBlocksInput() && value == route.value && value.stage in replyStages &&
            value.replyModeRevision == monitor.mode.value.revision &&
            live.copy(canReply = source.canReply) == source && (!requireAction || live.canReply) &&
            (!value.replyIncoming || incomingCurrent() && incoming.front?.item?.id == source.id)
    }

    fun messageReplyCurrent(id: String): Boolean = route.value.let {
        it.selectedId == id && it.stage !in setOf(RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT) &&
            replyOwnerCurrent(it) && (it.stage != RidePageStage.MESSAGE_SENDING || quickSnapshotCurrent(it))
    }

    fun messageReplyResultCurrent(id: String): Boolean = route.value.let {
        it.selectedId == id && it.stage in setOf(RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT) &&
            replyOwnerCurrent(it, requireAction = false)
    }

    private fun replyDefault(value: Route): Route? = if (value.replyIncoming) null else
        Route(RidePageStage.MESSAGE, value.selectedId, readStatus = MessageReadStatus.COMPLETE)

    private fun beginReply(value: Route) {
        val source = selectedMessage()?.takeIf { it.canReply } ?: return
        if (!messagesReady() || callSource() != null || popupBlocksInput()) return
        val fromIncoming = value.stage == RidePageStage.MESSAGE_READ
        if (fromIncoming && !incomingCurrent()) return
        incoming.interruptSpeech()
        navigate(RidePageStage.MESSAGE_CATEGORY, source.id)
        route.value = route.value.copy(replySource = source, replyIncoming = fromIncoming,
            replyModeRevision = monitor.mode.value.revision)
        restartTimeout()
    }

    private fun replyStage(stage: RidePageStage, text: String? = null,
        error: RideReplyAvailability? = null, result: MessageReplyResult? = null) {
        val retained = route.value
        navigate(stage, retained.selectedId)
        route.value = route.value.copy(replySource = retained.replySource, replyIncoming = retained.replyIncoming,
            replyModeRevision = retained.replyModeRevision, replyChoice = retained.replyChoice,
            replyQuickChoices = retained.replyQuickChoices.takeIf { stage in setOf(RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_SENDING) },
            replyText = text, replyError = error, replyResult = result)
        restartTimeout()
    }

    private fun quickSnapshotCurrent(value: Route) = value.replyQuickChoices?.let {
        it == quickReplies.value && quickRepliesCurrent(it) } != false

    private fun syncQuickReplies(): Boolean {
        val current = route.value
        if (current.stage != RidePageStage.MESSAGE_QUICK_LIST) return false
        val latest = quickReplies.value.toList()
        if (current.replyQuickChoices == latest) return false
        pending?.cancel()
        route.value = current.copy(replyQuickChoices = latest,
            replyChoice = current.replyChoice.coerceIn(0, latest.lastIndex.coerceAtLeast(0)),
            revision = current.revision + 1, fence = monitor.sequence)
        restartTimeout()
        return true
    }

    private fun openQuickReplies(choice: Int = 0) {
        replyStage(RidePageStage.MESSAGE_QUICK_LIST)
        val latest = quickReplies.value.toList()
        route.value = route.value.copy(replyQuickChoices = latest,
            replyChoice = choice.coerceIn(0, latest.lastIndex.coerceAtLeast(0)))
        restartTimeout()
    }

    private fun exitReply(forget: Boolean, home: Boolean = false) {
        val retained = route.value
        if (retained.stage !in replyStages) return
        if (home) {
            incoming.home(); messageDefault = null
            navigate(RidePageStage.MENU)
        } else if (retained.replyIncoming) {
            if (forget && incoming.front?.item?.id == retained.selectedId) incoming.yield(forget = true)
            val saved = messageDefault
            messageDefault = null
            restore(saved)
        } else restore(replyDefault(retained))
        syncIncoming()
    }

    private fun startReplyRecognition() {
        val current = route.value
        if (!replyOwnerCurrent(current)) return
        val available = replyAvailability()
        if (available != RideReplyAvailability.READY) {
            replyStage(RidePageStage.MESSAGE_TRY_AGAIN, error = available)
            return
        }
        replyStage(RidePageStage.MESSAGE_LISTENING)
        val requested = route.value
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            val text = try {
                if (!replyOwnerCurrent(requested)) return@launch
                RideMessageReply.text(recognizeReply(requested.selectedId!!))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (!replyOwnerCurrent(requested)) return@launch
            if (text == null) replyStage(RidePageStage.MESSAGE_TRY_AGAIN, error = RideReplyAvailability.UNAVAILABLE)
            else showReplyConfirmation(text)
        } ?: return
        replyJob = job
        job.start()
    }

    private fun showReplyConfirmation(text: String) {
        replyStage(RidePageStage.MESSAGE_CONFIRMATION, text)
        val requested = route.value
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            try {
                if (replyOwnerCurrent(requested)) confirmReply(requested.selectedId!!, text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // A failed readback remains an explicit review page; completion never sends.
                if (replyOwnerCurrent(requested)) route.value = requested.copy(replyError = RideReplyAvailability.UNAVAILABLE,
                    revision = requested.revision + 1, fence = monitor.sequence)
            } finally {
                if (replyJob === currentCoroutineContext()[Job]) {
                    replyJob = null
                    restartTimeout()
                }
            }
        } ?: return
        replyJob = job
        job.start()
        restartTimeout() // Managed readback blocks confirmation's auto-return timer.
    }

    private fun sendReply(quickText: String? = null) {
        val confirmed = route.value
        val text = if (confirmed.stage == RidePageStage.MESSAGE_QUICK_LIST) quickText else confirmed.replyText
        if (text == null || confirmed.stage !in setOf(RidePageStage.MESSAGE_CONFIRMATION, RidePageStage.MESSAGE_QUICK_LIST) ||
            !replyOwnerCurrent(confirmed) || !quickSnapshotCurrent(confirmed)) return
        replyStage(RidePageStage.MESSAGE_SENDING, text)
        val requested = route.value
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            // The OEM minimum display interval runs alongside dispatch, never before it.
            val minimum = async(start = CoroutineStart.UNDISPATCHED) { delay(1_000) }
            val result = try {
                if (!replyOwnerCurrent(requested)) return@launch
                if (!quickSnapshotCurrent(requested)) {
                    openQuickReplies(requested.replyChoice)
                    return@launch
                }
                dispatchReply(requested.selectedId!!, text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MessageReplyResult.UNAVAILABLE }
            minimum.await()
            if (replyOwnerCurrent(requested, requireAction = false)) showReplyResult(result)
        } ?: return
        replyJob = job
        job.start()
    }

    private fun showReplyResult(result: MessageReplyResult) {
        replyStage(if (result == MessageReplyResult.DISPATCHED) RidePageStage.MESSAGE_SENT else RidePageStage.MESSAGE_NOT_SENT,
            result = result) // Clear the transcript before reporting the source-dispatch result.
        val requested = route.value
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            try {
                if (!replyOwnerCurrent(requested, requireAction = false)) return@launch
                reportReplyResult(requested.selectedId!!, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Native failure cleanup still releases the retained result source. */ }
            if (!replyOwnerCurrent(requested, requireAction = false)) return@launch
            delay(1_000)
            if (replyOwnerCurrent(requested, requireAction = false)) exitReply(forget = true)
        } ?: return
        replyJob = job
        job.start()
    }

    private fun CoroutineScope.handleReply(function: Function, value: Route, modeRevision: Long) {
        if (!fresh(value, modeRevision) || !replyOwnerCurrent(value, value.stage !in replyOutcomeStages)) {
            exitReply(forget = false)
            return
        }
        when (function) {
            Function.MENU -> exitReply(forget = true, home = true)
            Function.BACK -> when (value.stage) {
                RidePageStage.MESSAGE_CONFIRMATION -> startReplyRecognition()
                RidePageStage.MESSAGE_QUICK_LIST -> {
                    replyStage(RidePageStage.MESSAGE_CATEGORY)
                    route.value = route.value.copy(replyChoice = 1)
                    restartTimeout()
                }
                else -> exitReply(forget = true)
            }
            Function.NEXT, Function.PREVIOUS -> if (value.stage in setOf(RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST)) {
                val last = if (value.stage == RidePageStage.MESSAGE_CATEGORY) RideMessageReply.categoryChoices.lastIndex
                    else value.replyQuickChoices.orEmpty().lastIndex.coerceAtLeast(0)
                val choice = (value.replyChoice + if (function == Function.NEXT) 1 else -1).coerceIn(0, last)
                if (choice != value.replyChoice) {
                    pending?.cancel()
                    route.value = value.copy(replyChoice = choice, revision = value.revision + 1, fence = monitor.sequence)
                    restartTimeout()
                }
            } else launch { if (fresh(value, modeRevision)) dispatchVolume(function) }
            Function.SELECT -> when (value.stage) {
                RidePageStage.MESSAGE_CATEGORY -> if (value.replyChoice == 0) startReplyRecognition()
                    else openQuickReplies()
                RidePageStage.MESSAGE_QUICK_LIST -> if (quickSnapshotCurrent(value))
                    value.replyQuickChoices?.getOrNull(value.replyChoice)?.let(RideMessageReply::text)?.let { sendReply(it) }
                RidePageStage.MESSAGE_TRY_AGAIN -> startReplyRecognition()
                RidePageStage.MESSAGE_CONFIRMATION -> sendReply()
                else -> Unit // Sending and results never retry a dispatch.
            }
            else -> Unit
        }
    }

    private fun replyProjection(value: Route): Gen1Projection.Page = RideMessageReply.page(
        value.stage, value.replyChoice, value.replyError, value.replyQuickChoices.orEmpty())

    private fun restore(previous: Route?) {
        val saved = previous?.let(::statusDefault)
        val valid = when (saved?.stage) {
            RidePageStage.MUSIC -> saved.selectedId == selectedId() && !calls.value.musicBlocked
            RidePageStage.NO_SESSION, RidePageStage.MENU, RidePageStage.VOLUME -> true
            RidePageStage.STATUS_MENU -> (weather != null || statusReport != null) && statusGate()
            RidePageStage.WEATHER -> weather != null && monitor.mode.value.function == Function.MODE_SAB && !calls.value.musicBlocked
            RidePageStage.MESSAGE -> messagesReady() && messages?.value?.messages?.any { it.id == saved.selectedId } == true
            RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL -> navigationPageReady() &&
                saved.selectedId == navigationSource()?.sourceId &&
                (saved.stage != RidePageStage.NAVIGATION_CANCEL || navigationSource()?.canCancel == true)
            else -> false
        }
        navigate(if (valid) saved!!.stage else RidePageStage.MENU, if (valid) saved?.selectedId else null)
        if (valid) route.value = route.value.copy(readStatus = if (saved?.readStatus == MessageReadStatus.READING)
            MessageReadStatus.FAILED else saved?.readStatus ?: MessageReadStatus.NONE,
            weatherInterrupted = saved?.stage == RidePageStage.WEATHER, statusChoice = saved?.statusChoice ?: 0)
        restartTimeout()
    }

    private fun incomingStatus(status: MessageReadStatus) {
        pending?.cancel()
        route.value = route.value.copy(readStatus = status, fence = monitor.sequence, revision = route.value.revision + 1)
        restartTimeout()
    }

    private fun syncIncoming() {
        if (closed) return // A closed page no longer owns the queue shared with its successor.
        if (!isCurrent()) { incoming.suspendContent(); return }
        if (incomingMode != messageMode.value) {
            incomingMode = messageMode.value
            if (route.value.stage in incomingStages) {
                messageJob?.cancel(); messageJob = null
                pending?.cancel()
                route.value = route.value.copy(fence = monitor.sequence, revision = route.value.revision + 1)
            }
        }
        val wasAvailable = incoming.available
        if (monitor.mode.value.function == Function.MODE_SAB && supported())
            incoming.sync(messages?.value ?: MessageInboxState(), messageMode.value)
        else incoming.suspendContent()
        if (wasAvailable != incoming.available) route.value = route.value.copy(revision = route.value.revision + 1)
        val current = route.value
        if (current.stage in replyStages) {
            if (!replyOwnerCurrent(current, requireAction = current.stage !in replyOutcomeStages)) exitReply(forget = false)
            return
        }
        if (current.stage in setOf(RidePageStage.DISABLED, RidePageStage.SPLASH, RidePageStage.STARTUP_FAILED,
                RidePageStage.UNSUPPORTED, RidePageStage.CALL, RidePageStage.CALL_CANCEL)) return
        if (!messagesReady() || callSource() != null || !isCurrent()) {
            incoming.interruptSpeech()
            if (current.stage in incomingStages) {
                restore(messageDefault)
                if (monitor.mode.value.function != Function.MODE_SAB || messages?.value?.access != MessageAccess.READY)
                    messageDefault = null
            }
            return
        }
        val entry = incoming.front
        if (entry == null) {
            if (current.stage in incomingStages) { val saved = messageDefault; messageDefault = null; restore(saved) }
            return
        }
        val stage = if (entry.phase == IncomingMessages.Phase.PROMPT) RidePageStage.MESSAGE_PROMPT else RidePageStage.MESSAGE_READ
        if (current.stage !in incomingStages) {
            if (messageDefault == null) messageDefault = statusDefault(current)
            navigate(stage, entry.item.id)
            incomingStatus(entry.status)
        } else if (current.selectedId != entry.item.id || current.stage != stage) {
            navigate(stage, entry.item.id)
            incomingStatus(entry.status)
        } else if (current.readStatus != entry.status) {
            incomingStatus(entry.status)
        }
        if (entry.status == MessageReadStatus.NONE && messageJob?.isActive != true) startIncomingSpeech(entry)
    }

    private fun startIncomingSpeech(entry: IncomingMessages.Entry) {
        val scope = owner ?: return
        val modeRevision = monitor.mode.value.revision
        incoming.update(entry.phase, MessageReadStatus.READING)
        incomingStatus(MessageReadStatus.READING)
        val requested = route.value
        restartTimeout()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var success = false
            try {
                if (!fresh(requested, modeRevision) || !messagesReady() || incoming.front?.item?.id != entry.item.id) return@launch
                if (entry.phase == IncomingMessages.Phase.PROMPT) promptMessage(entry.item.id, entry.mode)
                else readMessage(entry.item.id)
                success = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep a failed page; a failed announcement cannot authorize auto-read. */ }
            finally {
                if (messageJob === currentCoroutineContext().job) messageJob = null
            }
            if (fresh(requested, modeRevision) && messagesReady() && incoming.front?.item?.id == entry.item.id) {
                if (success && entry.phase == IncomingMessages.Phase.PROMPT && entry.mode == IncomingMessageMode.AUTO_READ)
                    incoming.update(IncomingMessages.Phase.READ, MessageReadStatus.NONE)
                else incoming.update(entry.phase, if (success) MessageReadStatus.COMPLETE else MessageReadStatus.FAILED)
                syncIncoming()
                restartTimeout()
            }
        }
        messageJob = job
        job.start()
    }
    private fun callSource() = callInbox?.value?.takeIf { it.access == CallAccess.READY }?.calls?.singleOrNull()
        ?.takeIf { monitor.mode.value.function == Function.MODE_SAB }
    private fun syncCall(value: CallInboxState) {
        if (monitor.mode.value.function != Function.MODE_SAB) { incomingEpisode = false; return }
        if (value.access == CallAccess.READY && value.calls.none { it.kind == CallKind.INCOMING })
            incomingEpisode = false
        val item = callSource()
        val current = route.value
        if (current.stage in setOf(RidePageStage.CALL, RidePageStage.CALL_CANCEL)) {
            if (item == null) { val saved = callDefault; callDefault = null; restore(saved); syncIncoming(); return }
            if (item.kind == CallKind.INCOMING) incomingEpisode = true
            if (item.id != current.callItem?.id || item.kind != current.callItem?.kind ||
                item.caller != current.callItem?.caller || item.isVideo != current.callItem?.isVideo) {
                navigate(RidePageStage.CALL, item.id, item)
            } else if (item.actions != current.callItem?.actions) {
                if (current.stage == RidePageStage.CALL_CANCEL && CallAction.HANG_UP !in item.actions) {
                    navigate(RidePageStage.CALL, item.id, item)
                    return
                }
                // A successful dispatch consumes actions synchronously; keep its result owner alive.
                pending?.cancel()
                route.value = current.copy(callItem = item, fence = monitor.sequence,
                    revision = current.revision + 1)
            }
            return
        }
        if (item?.kind == CallKind.INCOMING && !incomingEpisode &&
            current.stage !in setOf(RidePageStage.DISABLED, RidePageStage.SPLASH,
                RidePageStage.STARTUP_FAILED, RidePageStage.UNSUPPORTED)) {
            incomingEpisode = true
            callDefault = if (current.stage in replyStages) replyDefault(current)
                else current.takeIf { it.stage !in incomingStages }?.let(::statusDefault)
            incoming.interruptSpeech()
            selection.resume(MainMenuSelection.Item.CALL)
            navigate(RidePageStage.CALL, item.id, item)
        }
    }
    private fun requestCall(requested: Route, modeRevision: Long, action: CallAction) {
        if (callJob?.isActive == true || requested.stage != RidePageStage.CALL ||
            action !in requested.callItem?.actions.orEmpty()) return
        val job = owner?.launch(start = CoroutineStart.LAZY) {
            val result = try {
                if (!fresh(requested, modeRevision) || callSource() != requested.callItem)
                    CallDispatchResult.STALE
                else dispatchCall(requested.selectedId!!, action)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CallDispatchResult.UNAVAILABLE }
            val current = route.value
            if (!closed && isCurrent() && monitor.mode.value.revision == modeRevision &&
                current.stage == RidePageStage.CALL && current.selectedId == requested.selectedId &&
                current.callItem?.kind == requested.callItem?.kind &&
                callSource()?.id == requested.selectedId)
                route.value = current.copy(revision = current.revision + 1, callResult = result)
        } ?: return
        callJob = job
        job.start()
    }
    private fun syncNavigation(value: NavigationObservation) {
        if (route.value.stage !in setOf(RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL)) return
        if (!navigationPageReady()) { navigate(RidePageStage.MENU); return }
        val id = value.sourceId.takeIf { value.status == NavigationStatus.OBSERVED }
        val current = route.value
        if (current.stage == RidePageStage.NAVIGATION_CANCEL &&
            (id == null || id != current.selectedId || !value.canCancel)) {
            navigate(RidePageStage.NAVIGATION, id)
        } else if (id != current.selectedId) {
            navigationJob?.cancel()
            repeatJob?.cancel()
            pending?.cancel()
            route.value = current.copy(selectedId = id, fence = monitor.sequence,
                revision = current.revision + 1,
                cancelResult = null)
        }
        if (!value.canRepeat) repeatJob?.cancel()
    }
    private fun updateSelection() {
        val before = selection.selected
        val entries = normalizeFunctionOrder(functionOrder.value).map {
            MainMenuSelection.Entry(it, (it == MainMenuSelection.Item.WEATHER && (weather != null || statusReport != null)) ||
                (it == MainMenuSelection.Item.NAVIGATION && navigationMenuReady()) ||
                (it == MainMenuSelection.Item.CALL && callSource() != null) ||
                (it == MainMenuSelection.Item.MESSAGE && messagesReady()) || it == MainMenuSelection.Item.VOLUME ||
                (it == MainMenuSelection.Item.MUSIC && !calls.value.musicBlocked))
        }
        selection.update(entries)
        menuItems = entries.filter { it.visible }.map { it.item }
        mutableState.value = mutableState.value.copy(menuItems = visibleMenuItems(), menuSelected = focusedMenuItem(),
            volumePercentage = phoneVolumePercentage(), weather = phoneWeather(), weatherCanRetry = phoneWeatherCanRetry(),
            statusChoice = phoneStatusChoice(), statusReport = phoneStatusReport(), statusCanReport = phoneStatusCanReport())
        if (before != selection.selected && route.value.stage !in statusStages && route.value.stage !in incomingStages && route.value.stage !in replyStages && route.value.stage !in setOf(RidePageStage.MESSAGE,
                RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL,
                RidePageStage.CALL, RidePageStage.CALL_CANCEL)) {
            pending?.cancel()
            actionJob?.cancel()
            route.value = route.value.copy(fence = monitor.sequence, revision = route.value.revision + 1)
        }
    }

    suspend fun run(): Nothing = coroutineScope {
        check(owner == null && !closed)
        owner = this
        worker = currentCoroutineContext()[Job]
        updateSelection()
        try {
            when (startupState) {
                RideStartupState.READY -> navigate(RidePageStage.MENU)
                RideStartupState.RUNNING, RideStartupState.FAILED -> navigate(RidePageStage.STARTUP_FAILED)
                RideStartupState.NEW -> {
                    navigate(RidePageStage.SPLASH)
                    startupJob = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            startup()
                            currentCoroutineContext().ensureActive()
                            if (!closed && isCurrent() && route.value.stage == RidePageStage.SPLASH) {
                                updateSelection()
                                navigate(RidePageStage.MENU)
                                callInbox?.value?.let(::syncCall)
                                syncIncoming()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (!closed && isCurrent() && route.value.stage == RidePageStage.SPLASH)
                                navigate(RidePageStage.STARTUP_FAILED)
                        }
                    }
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                autoReturn.collect { restartTimeout() } // Native preference changes take effect immediately.
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                combine(volume, calls, weather ?: MutableStateFlow(RideWeatherState()), monitor.mode, route) { _, _, _, _, _ -> Unit }
                    .collect {
                        // Phone-only data must update even when the meter projection is identical.
                        mutableState.value = mutableState.value.copy(
                            volumePercentage = phoneVolumePercentage(), weather = phoneWeather(), weatherCanRetry = phoneWeatherCanRetry(),
                            statusChoice = phoneStatusChoice(), statusReport = phoneStatusReport(), statusCanReport = phoneStatusCanReport())
                    }
            }
            launch(start = CoroutineStart.UNDISPATCHED) { messageMode.collect { syncIncoming() } }
            launch(start = CoroutineStart.UNDISPATCHED) { quickReplies.collect { syncQuickReplies() } }
            launch(start = CoroutineStart.UNDISPATCHED) {
                functionOrder.collect {
                    updateSelection()
                    if (route.value.stage in statusStages || route.value.stage in incomingStages || route.value.stage in replyStages || route.value.stage in setOf(RidePageStage.MESSAGE, RidePageStage.NAVIGATION,
                            RidePageStage.NAVIGATION_CANCEL, RidePageStage.CALL,
                            RidePageStage.CALL_CANCEL)) return@collect
                    // Order changes can alter arrows without changing the focused item's identity.
                    pending?.cancel()
                    actionJob?.cancel()
                    route.value = route.value.copy(fence = monitor.sequence, revision = route.value.revision + 1)
                }
            }
            statusReport?.let { source -> launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect { value ->
                    val kind = value.kind
                    if (kind != null && statusReportCurrent(kind)) {
                        if (value.stage == RideStatusReportStage.COMPLETE) statusMenu()
                        else route.value = route.value.copy(statusState = value)
                    }
                }
            } }
            weather?.let { source -> launch {
                source.collect { value ->
                    if (weatherReportCurrent() && value.stage == RideWeatherStage.COMPLETE)
                        statusMenu()
                }
            } }
            launch(start = CoroutineStart.UNDISPATCHED) {
                var previousModeRevision = monitor.mode.value.revision
                monitor.mode.collect { mode ->
                    val changed = mode.revision != previousModeRevision
                    previousModeRevision = mode.revision
                    if (route.value.stage in statusStages && (mode.function != Function.MODE_SAB || changed))
                        navigate(RidePageStage.MENU)
                    if (route.value.stage in replyStages && (mode.function != Function.MODE_SAB || changed))
                        exitReply(forget = false)
                    if (route.value.stage == RidePageStage.MESSAGE &&
                        (mode.function != Function.MODE_SAB || changed))
                        navigate(RidePageStage.MENU)
                    if (route.value.stage in incomingStages && changed) {
                        incoming.interruptSpeech()
                        messageJob?.cancel(); messageJob = null
                        pending?.cancel()
                        incomingStatus(incoming.front?.status ?: MessageReadStatus.FAILED)
                    }
                    if (route.value.stage in setOf(RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL) &&
                        (mode.function != Function.MODE_SAB || changed))
                        navigate(RidePageStage.MENU)
                    if (mode.function != Function.MODE_SAB) incomingEpisode = false
                    if (route.value.stage in setOf(RidePageStage.CALL, RidePageStage.CALL_CANCEL)) {
                        if (mode.function != Function.MODE_SAB) navigate(RidePageStage.MENU)
                        else if (changed) {
                            callJob?.cancel()
                            pending?.cancel()
                            route.value = route.value.copy(fence = monitor.sequence,
                                revision = route.value.revision + 1, callResult = null)
                        }
                    }
                    updateSelection()
                    callInbox?.value?.let(::syncCall)
                    syncIncoming()
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                combine(media, calls) { _, _ -> Unit }.collect {
                    updateSelection()
                    if (route.value.stage in setOf(RidePageStage.WEATHER, RidePageStage.STATUS_REPORT) && !statusGate())
                        navigate(RidePageStage.MENU)
                    if (route.value.stage == RidePageStage.MUSIC &&
                        route.value.selectedId != selectedId()) {
                        actionJob?.cancel()
                        navigate(RidePageStage.MENU)
                    }
                    if (route.value.stage == RidePageStage.MESSAGE && !messagesReady())
                        navigate(RidePageStage.MENU)
                    if (route.value.stage in setOf(RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL) &&
                        !navigationPageReady()) navigate(RidePageStage.MENU)
                    syncIncoming()
                }
            }
            messages?.let { source -> launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect {
                    updateSelection()
                    if (route.value.stage == RidePageStage.MESSAGE) {
                        if (!messagesReady() || (route.value.selectedId != null && selectedMessage() == null))
                            navigate(RidePageStage.MENU)
                        else if (route.value.selectedId == null && it.messages.isNotEmpty())
                            navigate(RidePageStage.MESSAGE, it.messages.first().id)
                    }
                    syncIncoming()
                }
            } }
            navigation?.let { source -> launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect {
                    updateSelection()
                    syncNavigation(it)
                }
            } }
            callInbox?.let { source -> launch(start = CoroutineStart.UNDISPATCHED) {
                source.collect { value ->
                    updateSelection()
                    syncCall(value)
                    syncIncoming()
                }
            } }
            launch(start = CoroutineStart.UNDISPATCHED) {
                monitor.inputs.collect { input ->
                    // HU belongs to the vehicle. Keep decoding mode notifications, but never
                    // forward its keys to an overlay, page, media, volume or call action.
                    if (monitor.mode.value.function != Function.MODE_SAB ||
                        input.modeRevision != monitor.mode.value.revision) return@collect
                    // One input dispatcher: an actionable overlay owns its keys before ordinary pages.
                    if (!closed && isCurrent() && interceptInput(input)) return@collect
                    callInbox?.value?.let(::syncCall)
                    syncIncoming()
                    syncQuickReplies()
                    updateSelection()
                    val current = route.value
                    if (closed || !isCurrent() || !supported() ||
                        current.stage !in actionStages ||
                        input.sequence <= current.fence || input.modeRevision != monitor.mode.value.revision) return@collect
                    handleAction(input.function, current, input.modeRevision)
                }
            }
            combine(combine(combine(route, media, calls, volume, monitor.mode) { _, _, _, _, _ -> Unit },
                statusReport ?: MutableStateFlow(RideStatusReportState())) { _, _ -> Unit },
                weather ?: MutableStateFlow(RideWeatherState()),
                messages ?: MutableStateFlow(MessageInboxState()),
                navigation ?: MutableStateFlow(NavigationObservation()),
                callInbox ?: MutableStateFlow(CallInboxState())) { _, _, _, _, _ -> display() }
                .distinctUntilChanged().collectLatest { value ->
                    mutableState.value = RidePageState(value.stage, volumeAvailable = value.volumeAvailable,
                        messageReadStatus = value.readStatus, navigationCancelResult = value.cancelResult,
                        callDispatchResult = value.callResult, messageRecoveryAvailable = value.messageRecoveryAvailable,
                        messageLabel = value.messageLabel, messageCanRead = value.messageCanRead,
                        messageCanReply = value.messageCanReply, replyText = value.replyText,
                        replyChoices = value.replyChoices, replyChoice = value.replyChoice,
                        replyError = value.replyError, replyResult = value.replyResult,
                        menuItems = visibleMenuItems(), menuSelected = focusedMenuItem(),
                        volumePercentage = phoneVolumePercentage(), weather = phoneWeather(), weatherCanRetry = phoneWeatherCanRetry(),
                        statusChoice = phoneStatusChoice(), statusReport = phoneStatusReport(), statusCanReport = phoneStatusCanReport())
                    val page = value.page ?: return@collectLatest
                    delay(10) // OEM downstream page publication delay; never wait for Splash ACK to enter Main.
                    if (!current(value)) return@collectLatest
                    val request = controls.submit(allowed = { current(value) }) { Gen1Projection.encode(page, it) }
                    pending = request
                    mutableState.value = RidePageState(value.stage, pending = true, volumeAvailable = value.volumeAvailable,
                        messageReadStatus = value.readStatus, navigationCancelResult = value.cancelResult,
                        callDispatchResult = value.callResult, messageRecoveryAvailable = value.messageRecoveryAvailable,
                        messageLabel = value.messageLabel, messageCanRead = value.messageCanRead,
                        messageCanReply = value.messageCanReply, replyText = value.replyText,
                        replyChoices = value.replyChoices, replyChoice = value.replyChoice,
                        replyError = value.replyError, replyResult = value.replyResult,
                        menuItems = visibleMenuItems(), menuSelected = focusedMenuItem(),
                        volumePercentage = phoneVolumePercentage(), weather = phoneWeather(), weatherCanRetry = phoneWeatherCanRetry(),
                        statusChoice = phoneStatusChoice(), statusReport = phoneStatusReport(), statusCanReport = phoneStatusCanReport())
                    try {
                        val receipt = request.await()
                        if (current(value)) mutableState.value = RidePageState(value.stage,
                            receiptStatus = receipt?.status(), volumeAvailable = value.volumeAvailable,
                            messageReadStatus = value.readStatus, navigationCancelResult = value.cancelResult,
                            callDispatchResult = value.callResult, messageRecoveryAvailable = value.messageRecoveryAvailable,
                            messageLabel = value.messageLabel, messageCanRead = value.messageCanRead,
                        messageCanReply = value.messageCanReply, replyText = value.replyText,
                        replyChoices = value.replyChoices, replyChoice = value.replyChoice,
                        replyError = value.replyError, replyResult = value.replyResult,
                            menuItems = visibleMenuItems(), menuSelected = focusedMenuItem(),
                            volumePercentage = phoneVolumePercentage(), weather = phoneWeather(), weatherCanRetry = phoneWeatherCanRetry(),
                            statusChoice = phoneStatusChoice(), statusReport = phoneStatusReport(), statusCanReport = phoneStatusCanReport())
                    } finally {
                        request.cancel()
                        if (pending === request) pending = null
                    }
                }
            awaitCancellation()
        } finally {
            releaseContent()
        }
    }

    private fun CoroutineScope.handleAction(function: Function, current: Route, modeRevision: Long) {
        if (function in setOf(Function.NEXT, Function.PREVIOUS, Function.SELECT, Function.BACK, Function.MENU))
            restartTimeout() // Reset before the handler, including ignored or bounded actions.
        if (function in setOf(Function.VOLUME_UP, Function.VOLUME_DOWN, Function.VOLUME_MUTE)) {
            if (current.stage in setOf(RidePageStage.WEATHER, RidePageStage.MESSAGE,
                    RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
                    RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL,
                    RidePageStage.CALL, RidePageStage.CALL_CANCEL)) {
                launch { if (fresh(current, modeRevision)) dispatchVolume(function) }
                return
            }
            actionJob?.cancel()
            actionJob = launch { if (fresh(current, modeRevision)) dispatchVolume(function) }
            return
        }
        if (current.stage in replyStages) {
            handleReply(function, current, modeRevision)
            return
        }
        when (current.stage) {
            RidePageStage.MENU -> when (function) {
                Function.NEXT -> { val before = selection.index; selection.next()
                    if (before != selection.index) navigate(RidePageStage.MENU) }
                Function.PREVIOUS -> { val before = selection.index; selection.previous()
                    if (before != selection.index) navigate(RidePageStage.MENU) }
                Function.SELECT -> selection.selected?.item?.let { enterFunction(it) }
                else -> Unit
            }
            RidePageStage.MUSIC -> when (function) {
                Function.MENU -> navigate(RidePageStage.MENU)
                Function.BACK, Function.SELECT, Function.NEXT, Function.PREVIOUS -> {
                    val id = current.selectedId
                    if (id != null && id == selectedId() && !calls.value.musicBlocked) {
                        actionJob?.cancel()
                        actionJob = launch { if (fresh(current, modeRevision) && id == selectedId() && !calls.value.musicBlocked)
                            dispatchMusic(function, id) }
                    }
                }
                else -> Unit
            }
            RidePageStage.VOLUME -> when (function) {
                Function.BACK, Function.MENU -> navigate(RidePageStage.MENU)
                Function.NEXT, Function.PREVIOUS -> {
                    actionJob?.cancel()
                    actionJob = launch { if (fresh(current, modeRevision)) dispatchVolume(function) }
                }
                else -> Unit
            }
            RidePageStage.NO_SESSION -> if (function == Function.BACK || function == Function.MENU)
                navigate(RidePageStage.MENU)
            RidePageStage.STATUS_MENU -> when (function) {
                Function.BACK, Function.MENU -> navigate(RidePageStage.MENU)
                Function.NEXT, Function.PREVIOUS -> if (fresh(current, modeRevision)) {
                    val choice = (current.statusChoice + if (function == Function.NEXT) 1 else -1).coerceIn(0, 3)
                    if (choice != current.statusChoice) {
                        pending?.cancel()
                        route.value = current.copy(statusChoice = choice, fence = monitor.sequence, revision = current.revision + 1)
                        restartTimeout()
                    }
                }
                Function.SELECT -> if (current.statusChoice == 0) startWeather() else startStatus()
                else -> Unit
            }
            RidePageStage.STATUS_REPORT -> when (function) {
                Function.BACK -> statusMenu()
                Function.MENU -> navigate(RidePageStage.MENU)
                Function.SELECT -> if (current.statusState?.stage == RideStatusReportStage.FAILED) startStatus()
                Function.NEXT, Function.PREVIOUS -> launch {
                    if (fresh(current, modeRevision)) dispatchVolume(function)
                }
                else -> Unit
            }
            RidePageStage.WEATHER -> when (function) {
                Function.BACK -> statusMenu()
                Function.MENU -> navigate(RidePageStage.MENU)
                Function.SELECT -> if ((current.weatherInterrupted || weather?.value?.stage == RideWeatherStage.FAILED) &&
                    monitor.mode.value.function == Function.MODE_SAB)
                    startWeather()
                Function.NEXT, Function.PREVIOUS -> {
                    // Volume adjustments must not cancel the speech/report owner.
                    launch { if (fresh(current, modeRevision)) dispatchVolume(function) }
                }
                else -> Unit
            }
            RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ -> when (function) {
                Function.MENU -> {
                    incoming.home(); messageDefault = null
                    navigate(RidePageStage.MENU)
                }
                Function.BACK -> { incoming.yield(forget = true); syncIncoming() }
                Function.NEXT, Function.PREVIOUS -> launch {
                    if (fresh(current, modeRevision) && messagesReady()) dispatchVolume(function)
                }
                Function.SELECT -> if (current.stage == RidePageStage.MESSAGE_READ && selectedMessage()?.canReply == true) {
                    beginReply(current)
                } else if (current.stage == RidePageStage.MESSAGE_PROMPT &&
                    messagesReady() && incoming.front?.mode == IncomingMessageMode.ASK_TO_LISTEN &&
                    selectedMessage() != null) {
                    messageJob?.cancel(); messageJob = null
                    incoming.update(IncomingMessages.Phase.READ, MessageReadStatus.NONE)
                    syncIncoming()
                }
                else -> Unit
            }
            RidePageStage.MESSAGE -> when (function) {
                Function.BACK, Function.MENU -> navigate(RidePageStage.MENU)
                Function.NEXT, Function.PREVIOUS -> if (messagesReady()) {
                    val items = messages?.value?.messages.orEmpty()
                    val index = items.indexOfFirst { it.id == current.selectedId }
                    val next = if (function == Function.NEXT) {
                        if (index < 0) 0 else (index + 1).coerceAtMost(items.lastIndex)
                    } else (index - 1).coerceAtLeast(0)
                    val id = items.getOrNull(next)?.id
                    if (id != null && id != current.selectedId) navigate(RidePageStage.MESSAGE, id)
                }
                Function.SELECT -> if (current.readStatus == MessageReadStatus.COMPLETE && selectedMessage()?.canReply == true) {
                    beginReply(current)
                } else if (messagesReady() && current.selectedId != null &&
                    current.readStatus != MessageReadStatus.READING && selectedMessage() != null) {
                    pending?.cancel()
                    val reading = current.copy(revision = current.revision + 1,
                        readStatus = MessageReadStatus.READING)
                    route.value = reading
                    messageJob?.cancel()
                    messageJob = launch {
                        val status = try {
                            if (!fresh(reading, modeRevision) || !messagesReady() ||
                                selectedMessage() == null) return@launch
                            readMessage(reading.selectedId!!)
                            MessageReadStatus.COMPLETE
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { MessageReadStatus.FAILED }
                        if (fresh(reading, modeRevision) && messagesReady() && selectedMessage() != null)
                            route.value = reading.copy(revision = reading.revision + 1, fence = monitor.sequence, readStatus = status)
                    }
                }
                else -> Unit
            }
            RidePageStage.NAVIGATION -> when (function) {
                Function.MENU -> navigate(RidePageStage.MENU)
                Function.BACK -> if (navigationSource()?.let {
                        it.sourceId == current.selectedId && it.canCancel
                    } == true) navigate(RidePageStage.NAVIGATION_CANCEL, current.selectedId)
                Function.SELECT -> if (navigationSource()?.let {
                        it.sourceId == current.selectedId && it.canRepeat
                    } == true) {
                    repeatJob?.cancel()
                    timeoutJob?.cancel()
                    val job = launch(start = CoroutineStart.LAZY) {
                        try {
                            if (fresh(current, modeRevision) &&
                                navigationSource()?.let {
                                    it.sourceId == current.selectedId && it.canRepeat
                                } == true) repeatNavigation(current.selectedId!!)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Source-bound speech reports its own failure. */ }
                        finally {
                            if (repeatJob === currentCoroutineContext()[Job]) {
                                repeatJob = null
                                if (!closed && route.value.stage == RidePageStage.NAVIGATION)
                                    restartTimeout()
                            }
                        }
                    }
                    repeatJob = job
                    job.start()
                }
                Function.NEXT, Function.PREVIOUS -> launch {
                    if (fresh(current, modeRevision)) dispatchVolume(function)
                }
                else -> Unit
            }
            RidePageStage.NAVIGATION_CANCEL -> when (function) {
                Function.BACK -> navigate(RidePageStage.NAVIGATION, current.selectedId)
                Function.MENU -> navigate(RidePageStage.MENU)
                Function.SELECT -> if (navigationSource()?.let {
                        it.sourceId == current.selectedId && it.canCancel
                    } == true) {
                    val id = current.selectedId!!
                    navigate(RidePageStage.NAVIGATION, id)
                    val requested = route.value
                    navigationJob = launch {
                        val result = try {
                            if (fresh(requested, modeRevision) &&
                                navigationSource()?.let { it.sourceId == id && it.canCancel } == true)
                                cancelNavigation(id) else NavigationCancelResult.STALE
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { NavigationCancelResult.UNAVAILABLE }
                        if (fresh(requested, modeRevision))
                            route.value = requested.copy(revision = requested.revision + 1,
                                cancelResult = result)
                    }
                }
                Function.NEXT, Function.PREVIOUS -> launch {
                    if (fresh(current, modeRevision)) dispatchVolume(function)
                }
                else -> Unit
            }
            RidePageStage.CALL -> when (function) {
                Function.MENU -> { callDefault = null; navigate(RidePageStage.MENU) }
                Function.BACK -> when (current.callItem?.kind) {
                    CallKind.INCOMING -> requestCall(current, modeRevision, CallAction.DECLINE)
                    CallKind.ONGOING -> if (CallAction.HANG_UP in current.callItem?.actions.orEmpty() &&
                        callSource() == current.callItem)
                        navigate(RidePageStage.CALL_CANCEL, current.selectedId, current.callItem)
                    else -> Unit
                }
                Function.SELECT -> if (current.callItem?.kind == CallKind.INCOMING)
                    requestCall(current, modeRevision, CallAction.ANSWER)
                Function.NEXT, Function.PREVIOUS -> launch {
                    if (fresh(current, modeRevision)) dispatchVolume(function)
                }
                else -> Unit
            }
            RidePageStage.CALL_CANCEL -> when (function) {
                Function.MENU -> { callDefault = null; navigate(RidePageStage.MENU) }
                Function.BACK -> navigate(RidePageStage.CALL, current.selectedId, current.callItem)
                Function.SELECT -> if (current.callItem?.kind == CallKind.ONGOING &&
                    CallAction.HANG_UP in current.callItem?.actions.orEmpty() && callSource() == current.callItem) {
                    navigate(RidePageStage.CALL, current.selectedId, current.callItem)
                    requestCall(route.value, modeRevision, CallAction.HANG_UP)
                }
                Function.NEXT, Function.PREVIOUS -> launch {
                    if (fresh(current, modeRevision)) dispatchVolume(function)
                }
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun fresh(value: Route, modeRevision: Long) = !closed && isCurrent() && value == route.value &&
        monitor.mode.value.function == Function.MODE_SAB &&
        modeRevision == monitor.mode.value.revision && !popupBlocksInput() &&
        (value.stage !in incomingStages || incomingCurrent() &&
            incoming.front?.item?.id == value.selectedId && selectedMessage() != null)
    private fun display(): Display {
        val current = route.value
        val available = volume.value != null && calls.value.volumeStream == volume.value?.stream
        fun result(stage: RidePageStage, page: Gen1Projection.Page? = null) =
            Display(stage, page, current.revision, monitor.mode.value.revision, available,
                current.readStatus, current.cancelResult, current.callResult, incoming.available,
                if (current.stage in replyStages) current.replySource?.let { it.sender ?: it.title }
                else incoming.front?.item?.let { it.sender ?: it.title }.takeIf { current.stage in incomingStages },
                current.stage == RidePageStage.MESSAGE_PROMPT && incoming.front?.mode == IncomingMessageMode.ASK_TO_LISTEN,
                current.stage in setOf(RidePageStage.MESSAGE, RidePageStage.MESSAGE_READ) && selectedMessage()?.canReply == true &&
                    (current.stage == RidePageStage.MESSAGE_READ || current.readStatus == MessageReadStatus.COMPLETE),
                current.replyText.takeIf { stage in replyStages },
                when (stage) {
                    RidePageStage.MESSAGE_CATEGORY -> RideMessageReply.categoryChoices
                    RidePageStage.MESSAGE_QUICK_LIST -> current.replyQuickChoices.orEmpty()
                    else -> emptyList()
                },
                current.replyChoice, current.replyError.takeIf { stage in replyStages },
                current.replyResult.takeIf { stage in replyStages }, phoneStatusChoice(), phoneStatusReport(), phoneStatusCanReport())
        if (closed || !isCurrent()) return result(RidePageStage.DISABLED)
        if (!supported()) return result(RidePageStage.UNSUPPORTED)
        if (current.stage in replyStages) return if (replyOwnerCurrent(current,
                requireAction = current.stage !in replyOutcomeStages))
            result(current.stage, replyProjection(current)) else result(RidePageStage.DISABLED)
        return when (current.stage) {
            RidePageStage.SPLASH, RidePageStage.STARTUP_FAILED -> result(current.stage,
                Gen1Projection.Menu(4, 4, line1 = "Honda", line2 = "RoadSync"))
            RidePageStage.MENU -> {
                val item = selection.selected
                result(RidePageStage.MENU, when (item?.item) {
                    MainMenuSelection.Item.WEATHER -> weather?.value?.let { WeatherProjection.menu(it,
                        labels.weather, labels.weatherLoading, labels.weatherUnavailable,
                        item.first, item.last) } ?: statusReport?.let {
                            Gen1Projection.Menu(17, 16, header = labels.statusReport,
                                up = if (item.first) null else Gen1Projection.Arrow.UP,
                                down = if (item.last) null else Gen1Projection.Arrow.DOWN,
                                right = Gen1Projection.Arrow.RIGHT)
                        }
                    MainMenuSelection.Item.MUSIC -> if (calls.value.musicBlocked) null else
                        MusicProjection.menu(media.value, labels.music, labels.playing,
                            labels.unknown, locale, false, first = item.first, last = item.last)
                    MainMenuSelection.Item.MESSAGE -> if (messagesReady())
                        MessageProjection.menu(labels.messages, item.first, item.last) else null
                    MainMenuSelection.Item.NAVIGATION -> if (navigationMenuReady())
                        NavigationProjection.menu(
                            if (navigation?.value?.status == NavigationStatus.OBSERVED) labels.navigating
                                else labels.navigation,
                            item.first, item.last,
                            navigation?.value?.instruction.takeIf {
                                navigation?.value?.status == NavigationStatus.OBSERVED
                            }) else null
                    MainMenuSelection.Item.CALL -> callSource()?.let {
                        CallProjection.menu(it, labels.call, item.first, item.last)
                    }
                    MainMenuSelection.Item.VOLUME -> VolumeProjection.menu(labels.volume,
                        first = item.first, last = item.last)
                    else -> null
                })
            }
            RidePageStage.MUSIC -> result(RidePageStage.MUSIC,
                if (current.selectedId == selectedId() && !calls.value.musicBlocked)
                    media.value?.let { MusicProjection.live(it, labels.unknown, locale, false) } else null)
            RidePageStage.NO_SESSION -> result(RidePageStage.NO_SESSION,
                Gen1Projection.Menu(14, 13, header = labels.choosePlayer, left = Gen1Projection.Arrow.LEFT, contentIcon = 111))
            RidePageStage.VOLUME -> result(RidePageStage.VOLUME, VolumeProjection.live(
                volume.value.takeIf { calls.value.volumeStream == it?.stream }, labels.volume))
            RidePageStage.STATUS_MENU -> result(RidePageStage.STATUS_MENU,
                if (phoneMenuCurrent()) Gen1Projection.Menu(17, 16, header = labels.statusReport,
                    up = if (current.statusChoice == 0) null else Gen1Projection.Arrow.UP,
                    down = if (current.statusChoice == 3) null else Gen1Projection.Arrow.DOWN,
                    left = Gen1Projection.Arrow.LEFT,
                    right = if (phoneStatusCanReport()) Gen1Projection.Arrow.RIGHT else null,
                    line1 = statusLabel(current.statusChoice)) else null)
            RidePageStage.STATUS_REPORT -> {
                val report = phoneStatusReport()
                result(RidePageStage.STATUS_REPORT, if (report == null) null else
                    if (report.stage == RideStatusReportStage.FAILED) Gen1Projection.Menu(17, 16,
                        header = labels.statusUnavailable, left = Gen1Projection.Arrow.LEFT,
                        right = Gen1Projection.Arrow.RIGHT, line1 = statusLabel(current.statusChoice))
                    else Gen1Projection.Live(17, 18, header = when (report.stage) {
                        RideStatusReportStage.SPEAKING -> labels.statusSpeaking
                        else -> labels.statusLoading
                    }, left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 1, 16),
                        contentIcon = if (report.stage == RideStatusReportStage.LOADING) 89 else null))
            }
            RidePageStage.WEATHER -> result(RidePageStage.WEATHER,
                if (current.weatherInterrupted || weather?.value?.stage == RideWeatherStage.FAILED)
                    Gen1Projection.Menu(17, 16, header = labels.weatherUnavailable,
                        left = Gen1Projection.Arrow.LEFT, right = Gen1Projection.Arrow.RIGHT_BIG, contentIcon = 91)
                else WeatherProjection.live(labels.weather, weather?.value?.stage == RideWeatherStage.LOADING))
            RidePageStage.MESSAGE -> if (!messagesReady() ||
                (current.selectedId != null && selectedMessage() == null)) result(RidePageStage.DISABLED)
                else result(RidePageStage.MESSAGE, MessageProjection.browse(
                    messages?.value?.messages.orEmpty(), current.selectedId, labels, current.readStatus))
            RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ -> {
                val entry = incoming.front?.takeIf { it.item.id == current.selectedId && selectedMessage() != null }
                if (!incomingCurrent() || entry == null) result(RidePageStage.DISABLED)
                else result(current.stage, MessageProjection.incoming(entry.item, labels.messages,
                    current.stage == RidePageStage.MESSAGE_PROMPT && entry.mode == IncomingMessageMode.ASK_TO_LISTEN,
                    current.stage == RidePageStage.MESSAGE_READ, canReply = selectedMessage()?.canReply == true))
            }
            RidePageStage.NAVIGATION -> if (!navigationPageReady()) result(RidePageStage.DISABLED)
                else {
                    val observed = navigationSource()?.takeIf { it.sourceId == current.selectedId }
                    val instruction = observed?.instruction
                    val header = if (current.cancelResult != null &&
                        current.cancelResult != NavigationCancelResult.DISPATCHED)
                        labels.navigationCancelUnavailable else labels.navigating
                    result(RidePageStage.NAVIGATION,
                        if (instruction?.maneuver != null) NavigationProjection.active(
                            instruction.maneuver, instruction.nextStep, header,
                            observed?.canCancel == true, observed?.canRepeat == true)
                        else NavigationProjection.waiting(
                            if (observed == null) labels.navigationWaiting else header,
                            observed?.canCancel == true, observed?.canRepeat == true))
                }
            RidePageStage.NAVIGATION_CANCEL -> if (!navigationPageReady()) result(RidePageStage.DISABLED)
                else result(RidePageStage.NAVIGATION_CANCEL,
                    if (navigationSource()?.let { it.sourceId == current.selectedId && it.canCancel } == true)
                        NavigationProjection.cancel(labels.navigationCancel)
                    else NavigationProjection.waiting(labels.navigationWaiting))
            RidePageStage.CALL -> {
                val item = callSource()?.takeIf { it.id == current.selectedId &&
                    it.kind == current.callItem?.kind }
                if (item == null) result(RidePageStage.DISABLED) else result(RidePageStage.CALL,
                    CallProjection.live(item,
                        if (current.callResult != null && current.callResult != CallDispatchResult.DISPATCHED)
                            labels.callUnavailable else labels.callIncoming,
                        if (current.callResult != null && current.callResult != CallDispatchResult.DISPATCHED)
                            labels.callUnavailable else labels.callOngoing))
            }
            RidePageStage.CALL_CANCEL -> {
                val item = callSource()
                if (item == null || item.id != current.selectedId || item.kind != CallKind.ONGOING ||
                    CallAction.HANG_UP !in item.actions) result(RidePageStage.DISABLED)
                else result(RidePageStage.CALL_CANCEL, CallProjection.cancel(labels.callEndConfirm))
            }
            else -> result(current.stage)
        }
    }
    private fun current(value: Display) = !closed && isCurrent() && value == display() &&
        value.page?.let { Gen1Projection.supports(it, capabilities) } == true

    override fun close() {
        releaseContent()
        worker?.cancel()
    }

    private fun releaseContent() {
        if (closed) return
        closed = true
        incoming.interruptSpeech()
        incoming.suspendContent()
        stopWeather()
        stopStatus()
        startupJob?.cancel(); actionJob?.cancel(); messageJob?.cancel(); replyJob?.cancel(); navigationJob?.cancel(); repeatJob?.cancel(); callJob?.cancel()
        timeoutJob?.cancel(); pending?.cancel(); pending = null
        route.value = Route()
        messageDefault = null; callDefault = null
        mutableState.value = RidePageState()
    }
}
