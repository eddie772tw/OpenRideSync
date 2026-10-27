package org.openridesync.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.session.PlaybackState
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.openridesync.app.DiagnosticsState
import org.openridesync.app.DiagnosticArchiveSummary
import org.openridesync.app.AccountState
import org.openridesync.app.auth.AuthState
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.calls.*
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.media.*
import org.openridesync.app.messages.*
import org.openridesync.app.runtime.*
import org.openridesync.app.navigation.MainTab
import org.openridesync.app.trips.*
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.openridesync.app.vehicles.*
import org.openridesync.app.weather.*
import org.openridesync.honda.wire.HondaWire.*
import org.openridesync.honda.wire.WeatherWire
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production content with explicitly synthetic state; no account, service or device host. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureScreenGalleryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var hostView: View
    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone

    @Before fun isolatePublicFixture() {
        originalLocale = Locale.getDefault()
        originalZone = TimeZone.getDefault()
        Locale.setDefault(Locale.TAIWAN)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val app = RuntimeEnvironment.getApplication()
        assertEquals(Application::class.java, app.javaClass)
        assertFalse(app.assets.list("").orEmpty().contains("auth-client.json"))
        for ((kind, name) in listOf("font" to "oem_ui_regular", "font" to "oem_ui_medium",
            "drawable" to "oem_ui_bluetooth", "drawable" to "oem_ui_music")) {
            assertEquals("Private UI resource must not enter gallery: $name", 0,
                app.resources.getIdentifier(name, kind, app.packageName))
        }
    }

    @After fun restoreFormatting() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test fun accountUnconfigured() {
        fixture("account") { AccountFixture(AuthStatus.UNAVAILABLE) }
        capture("account-unconfigured")
    }

    @Test fun accountSignedOut() {
        fixture("account") { AccountFixture(AuthStatus.SIGNED_OUT) }
        capture("account-signed-out")
    }

    @Composable private fun AccountFixture(status: AuthStatus) {
        AccountScreen(AuthState(status), AccountState(), null, null,
            {}, {}, {}, {}, {}, {}, {}, {}, onBack = {}, showFeatureLinks = false)
    }

    @Test fun permissions() {
        // Only reads Robolectric's local permission shadows. No button is clicked, no OS request.
        fixture("permissions") { PermissionsScreen({}) }
        capture("permissions-top")
    }

    @Test fun garageLocalEmpty() {
        fixture("vehicles") { VehicleCatalogContent(VehicleState(garageLoaded = true), true) }
        capture("garage-local-empty")
    }

    @Test fun garageAddCatalogEmpty() {
        fixture("vehicles") { VehicleCatalogContent(VehicleState(browsingCatalog = true, catalogLoaded = true), true) }
        capture("garage-add-catalog-empty")
    }

    private fun model(label: String = "合成測試車款") = Model.newBuilder().setModelLabel(label)
        .setModelSeriesLabel("synthetic-series").addModelYears(2026).addModelYears(2025).addModelYears(2024)
        .setDisplacement(125).setMeter(Meter.newBuilder().setGeneration(1)).build()

    @Test fun garageAddCatalog() {
        fixture("vehicles") { VehicleCatalogContent(VehicleState(browsingCatalog = true, catalogLoaded = true,
            catalogs = (1..3).map { ModelCatalog.newBuilder().setModel(model("合成測試車款 $it")).setModelCount(2).build() }), true) }
        capture("garage-add-catalog")
    }

    @Test fun garageModelSeries() {
        val series = ModelCatalog.newBuilder().setModel(model()).setModelCount(2).build()
        fixture("vehicles") { VehicleCatalogContent(VehicleState(browsingCatalog = true, activeSeries = series,
            seriesLoaded = true, models = listOf(model("合成型號 A"), model("合成型號 B"))), true) }
        capture("garage-model-series")
    }

    @Test fun garageModelYear() {
        fixture("vehicles") { VehicleCatalogContent(VehicleState(browsingCatalog = true, detail = model(), detailYear = 0), true) }
        capture("garage-model-year")
    }

    @Test fun garageDetail() {
        fixture("vehicles") { VehicleCatalogContent(VehicleState(browsingCatalog = true, detail = model(), detailYear = 2025), true) }
        capture("garage-detail")
    }

    @Test fun diagnostics() {
        fixture("diagnostics") { DiagnosticsContent(DiagnosticsState(), AuthState(AuthStatus.UNAVAILABLE), {}, {}, {}) }
        capture("diagnostics-unconfigured")
    }

    @Test fun mediaPermission() {
        media(MediaPageState(signedIn = true, observing = true, media = MediaState(access = MediaAccess.REQUIRED)))
        capture("media-permission")
    }

    @Test fun mediaEmpty() {
        media(MediaPageState(signedIn = true, observing = true, media = MediaState(access = MediaAccess.GRANTED)))
        capture("media-empty")
    }

    @Test fun mediaTrackControl() {
        media(MediaPageState(signedIn = true, observing = true,
            media = MediaState(access = MediaAccess.GRANTED,
                sessions = listOf(MediaSessionChoice("synthetic-player", "合成測試播放器", "synthetic.invalid")),
                selectedId = "synthetic-player", title = "合成測試曲目", playback = MediaPlayback.PAUSED,
                actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)))
        capture("media-track-control")
    }

    @Test fun mediaSelector() {
        media(MediaPageState(signedIn = true, observing = true,
            media = MediaState(access = MediaAccess.GRANTED,
                sessions = (1..4).map { MediaSessionChoice("synthetic-player-$it", "合成測試播放器 $it", "synthetic.invalid.$it") })))
        capture("media-selector")
    }

    @Test fun favoriteMusicEmpty() {
        fixture("music-apps") { FavoriteMusicContent(FavoriteMusicState(signedIn = true, observing = true), {}, {}, {}) }
        capture("favorite-music-empty")
    }

    @Test fun favoriteMusicSelection() {
        // Only nonexistent synthetic package IDs: host icon lookups return no icon, never a real app.
        fixture("music-apps") { FavoriteMusicContent(FavoriteMusicState(signedIn = true, observing = true,
            apps = (1..4).map { FavoriteMusicApp("synthetic.invalid.player$it", "合成常用播放器 $it") },
            selected = "synthetic.invalid.player2"), {}, {}, {}) }
        capture("favorite-music-selection")
    }

    @Test fun favoriteMusicError() {
        fixture("music-apps") { FavoriteMusicContent(FavoriteMusicState(signedIn = true, observing = true,
            staleSelection = true, selected = "synthetic.invalid.removed"), {}, {}, {}) }
        capture("favorite-music-error")
    }

    @Test fun ridePreview() {
        var externalActions = 0
        fixture("ride-preview") { RidePreviewScreen({}, { externalActions++ }, { externalActions++ }) }
        compose.onNodeWithTag("ride_preview_banner").assertExists()
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        // Status, display and the bounded sidecard scroll independently. Select the sidecard
        // explicitly, without invoking either real-media/weather callback.
        val controlsScroll = hasAnyAncestor(hasTestTag("preview_controls_panel"))
        capture("ride-preview-controls", scrollMatcher = controlsScroll)
        for (mode in listOf("HU", "未知", "SAB")) {
            compose.onNodeWithTag("preview_mode_$mode").performScrollTo().performClick()
            for ((page, label) in listOf("MUSIC" to "音樂", "VOLUME" to "音量", "MENU" to "主選單")) {
                compose.onNodeWithTag("preview_page_$page").performScrollTo().performClick()
                compose.onNodeWithTag("preview_page_$page").assertIsSelected()
                if (mode == "HU") {
                    compose.onNodeWithTag("ride_phone_locked").assertExists()
                    compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
                } else {
                    compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
                    compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText(label)))
                }
            }
            if (mode != "SAB") compose.onNodeWithTag("preview_page_WEATHER").assertIsNotEnabled()
        }
        compose.onNodeWithTag("preview_page_WEATHER").performScrollTo().performClick()
        compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText("天氣匯報")))
        compose.onNodeWithTag("preview_page_MESSAGE").performScrollTo().performClick()
        for ((status, label) in listOf("NONE" to "訊息通知：上下選擇，確認播報，返回離開。",
            "READING" to "正在播報選定的通知預覽；返回可停止。", "COMPLETE" to "按確認選擇回覆方式，或上下切換訊息。",
            "FAILED" to "播報未完成，請檢查音訊、勿擾與通知存取，再自行決定是否重試。")) {
            compose.onNodeWithTag("preview_message_$status").performScrollTo().performClick()
            compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText(label)))
            if (status == "COMPLETE") compose.onNodeWithTag("ride_action_SELECT").assertTextContains("回覆…")
        }
        compose.onNodeWithTag("preview_mode_HU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_phone_locked").assertExists()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("preview_page_MENU").assertIsSelected()
        compose.onNodeWithTag("preview_page_MESSAGE").assertIsNotEnabled()
        compose.onNodeWithTag("preview_page_WEATHER").assertIsNotEnabled()
        capture("ride-preview-hu-controls", scrollMatcher = controlsScroll)
        compose.onNodeWithTag("preview_mode_SAB").performScrollTo().performClick()
        compose.onNodeWithTag("preview_page_MUSIC").performScrollTo().performClick()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_controls_panel").assertDoesNotExist()
        compose.onNodeWithTag("ride_phone_locked").assertDoesNotExist()
        compose.onNodeWithTag("ride_display_state").assert(hasAnyDescendant(hasText("音樂")))
        capture("ride-preview-content", scrollMatcher = hasAnyDescendant(hasTestTag("ride_display_state")))
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.onNodeWithTag("ride_action_BACK").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, externalActions) }
    }

    @Test fun diagnosticsAtCapacity() {
        val archive = DiagnosticArchiveSummary("00000000-0000-0000-0000-000000000001", 8387500,
            1792108800000L, 12000, 3, false)
        val state = mutableStateOf(DiagnosticsState(message = "合成容量上限狀態", sizeLimited = true,
            archives = listOf(archive)))
        var resumed = 0
        val exports = mutableListOf<String>()
        fixture("diagnostics") { DiagnosticsContent(state.value, AuthState(AuthStatus.UNAVAILABLE), {}, {}, {},
            onArchive = { resumed++ }, onExportArchive = exports::add) }
        capture("diagnostics-capacity")
        compose.onNodeWithTag("diagnostics_archive").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, resumed); assertTrue(exports.isEmpty()) }
        compose.onNodeWithTag("diagnostics_archive_cancel").performClick()
        compose.runOnIdle { assertEquals(0, resumed) }
        compose.onNodeWithTag("diagnostics_archive").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_archive_confirm").performClick()
        compose.runOnIdle { assertEquals(1, resumed); state.value = state.value.copy(sizeLimited = false, exportPending = true) }
        compose.onNodeWithTag("diagnostics_archive").assertIsNotEnabled()
        compose.onNodeWithTag("diagnostics_export").assertIsNotEnabled()
        compose.onNodeWithTag("diagnostics_archive_export_${archive.id}").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(exportPending = false) }
        compose.onNodeWithTag("diagnostics_archive_export_${archive.id}").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(archive.id), exports) }
    }

    private fun media(state: MediaPageState) = fixture("media") {
        MediaContent(state, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun weatherIdle() {
        weather(WeatherPageState(signedIn = true, observing = true, locationGranted = true))
        capture("weather-idle")
    }

    @Test fun weatherData() {
        val reading = WeatherReading(26f, 801, "合成地點（非即時天氣）", "合成預報：晴時多雲", "Clouds", true)
        weather(WeatherPageState(signedIn = true, observing = true, locationGranted = true,
            current = CurrentWeather(WeatherWire.Weather.getDefaultInstance(), reading),
            forecast = ForecastWeather(WeatherWire.WeatherForecast.getDefaultInstance(),
                listOf(1, 3, 5).map { HourlyWeather(it, reading.copy(celsius = 26f - it)) }),
            fetchedAtMillis = 1792108800000L))
        capture("weather-data")
    }

    @Test fun weatherError() {
        weather(WeatherPageState(signedIn = true, observing = true, locationGranted = true, errorCode = "NETWORK"))
        capture("weather-error")
    }

    private fun weather(state: WeatherPageState) = fixture("weather") { WeatherContent(state, {}, {}, {}, {}, {}, {}) }

    @Test fun tripsList() {
        val days = (0..7).map(::day)
        trips(TripState(hasLoaded = true, days = days,
            summary = TripsSummary.newBuilder().setTotalDistance(84000f).setTotalTime(10800L).setTotalDriveDay(8).build()))
        capture("trips-list")
    }

    @Test fun tripsDetail() {
        trips(TripState(hasLoaded = true, selectedDay = day(0),
            details = ListTripDetailsResponse.newBuilder().setTotalDistance(12000f).setTotalTime(1800L).setAvgSpeed(6.67f)
                .addDailyTripDetails(TripDetail.newBuilder().setTripUuid("synthetic-trip").setTotalDistance(12000f).setAvgSpeed(6.67f)).build()))
        capture("trips-detail")
    }

    @Test fun tripsError() {
        trips(TripState(errorCode = "UNAVAILABLE"))
        capture("trips-error")
    }

    private fun trips(state: TripState) = fixture("trips") { TripHistoryContent(state, true, {}, {}, {}, {}, {}, {}) }
    private fun day(index: Int): DailyTrips = DailyTrips.newBuilder()
        .setTripDate(1792108800L - index * 86400L).setStartTime(1792137600L - index * 86400L)
        .setEndTime(1792139400L - index * 86400L).addTripUuids("synthetic-$index")
        .setTotalDistance(12000f).setTotalTime(1800L).setAvgSpeed(6.67f)
        .addVehicleDisplayName("合成測試車輛").build()

    @Test fun ridePreparation() {
        ride(RideState(), false)
        capture("ride-preparation")
    }

    @Test fun ridePermissionPending() {
        ride(RideState(), true)
        capture("ride-permission-pending")
    }

    @Test fun rideStatusConnectedSynthetic() {
        ride(RideState(RideStage.CONNECTED, name = "合成測試車輛（非實車連線）"), false)
        capture("ride-status-connected")
    }

    @Test fun rideStatusError() {
        ride(RideState(RideStage.FAILED, name = "合成測試車輛", errorCode = "TIMEOUT"), false)
        capture("ride-status-error")
    }

    @Test fun rideStatusReportMenuSpeakingAndUnavailable() {
        val page = mutableStateOf(RidePageState(RidePageStage.STATUS_MENU, statusChoice = 2, statusCanReport = true))
        fixture("ride") {
            RideStatusLayout(RideDeviceStatus(76, mobile = RideSignal(SignalAvailability.READY, 3, "合成電信")),
                statusSelected = 2) {
                RideContent(RideState(RideStage.CONNECTED, name = "合成測試車輛", mode = Gen1Input.Function.MODE_SAB),
                    false, null, page.value, {}, {}, {}, {}, {}, {}, onAction = {})
            }
        }
        capture("ride-status-report-menu", scrollIndex = 1)
        compose.runOnIdle { page.value = page.value.copy(stage = RidePageStage.STATUS_REPORT, statusCanReport = false,
            statusReport = RideStatusReportState(RideStatusKind.MOBILE, RideStatusReportStage.SPEAKING,
                "合成行動網路，訊號三格，合成電信；不會播放語音。")) }
        capture("ride-status-report-speaking", scrollIndex = 1)
        compose.runOnIdle { page.value = page.value.copy(statusCanReport = true,
            statusReport = RideStatusReportState(RideStatusKind.MOBILE, RideStatusReportStage.FAILED, error = "UNAVAILABLE")) }
        capture("ride-status-report-failed", scrollIndex = 1)
    }

    @Test fun rideFavoriteRecoveryRequiresExplicitActionAndPreservesSelection() {
        val state = mutableStateOf(RideState(RideStage.CONNECTING, name = "合成測試車輛"))
        val media = mutableStateOf<MediaState?>(null)
        var requests = 0
        fixture("ride") {
            RideContent(state.value, false, media.value, RidePageState(RidePageStage.MUSIC),
                {}, {}, {}, {}, {}, {}, onRetryFavorite = {
                    requests++
                    state.value = state.value.copy(favoriteStart = FavoriteStartStatus.STARTING)
                })
        }
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(stage = RideStage.CONNECTED,
            favoriteStart = FavoriteStartStatus.OPEN_APP_REQUIRED) }
        capture("ride-favorite-recovery")
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("ride_retry_favorite").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ride_retry_favorite").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, requests)
            state.value = state.value.copy(favoriteStart = FavoriteStartStatus.PLAY_REQUESTED)
            media.value = MediaState(access = MediaAccess.GRANTED, selectedId = "synthetic-player",
                sessions = listOf(MediaSessionChoice("synthetic-player", "合成已選播放器", "synthetic.invalid")))
        }
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.runOnIdle { media.value = null }
        compose.onNodeWithTag("ride_retry_favorite").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(stage = RideStage.STOPPING) }
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun messagesInbox() {
        fixture("messages") {
            MessagesContent(MessagesPageState(signedIn = true, observing = true,
                inbox = MessageInboxState(MessageAccess.READY, listOf(
                    MessageItem("synthetic", "合成訊息 App", "synthetic.invalid", "合成測試對話", "這是離線畫面測試內容，不是真實通知。", 1, true)))),
                {}, {}, {}, {}, { _, _ -> })
        }
        capture("messages-inbox")
        compose.onNodeWithTag("message_reply_synthetic").performScrollTo().performClick()
        capture("messages-reply-editor", scrollIndex = 0)
        compose.onNodeWithTag("message_reply_text").performTextInput("這是合成回覆，只用於本機畫面驗證。")
        compose.onNodeWithTag("message_reply_review").performScrollTo().performClick()
        capture("messages-reply-confirm")
    }

    @Test fun callsIncomingAndOngoing() {
        fixture("calls") { CallsContent(CallsPageState(signedIn = true, observing = true,
            inbox = CallInboxState(CallAccess.READY, listOf(
                CallItem("incoming", "合成通話 App", "synthetic.invalid", "合成來電對象",
                    CallKind.INCOMING, setOf(CallAction.ANSWER, CallAction.DECLINE), true),
                CallItem("ongoing", "合成通話 App", "synthetic.invalid", "合成通話對象",
                    CallKind.ONGOING, setOf(CallAction.HANG_UP), false)))), {}, {}, {}, {}, { _, _ -> }) }
        capture("calls-incoming-ongoing")
    }

    @Test fun callsPermission() {
        fixture("calls") { CallsContent(CallsPageState(signedIn = true, observing = true,
            inbox = CallInboxState(CallAccess.ACCESS_REQUIRED)), {}, {}, {}, {}, { _, _ -> }) }
        capture("calls-permission")
    }

    private fun ride(state: RideState, pending: Boolean) = fixture("ride") {
        RideContent(state, pending, null, RidePageState(), {}, {}, {}, {}, {}, {})
    }

    private fun fixture(route: String, content: @Composable () -> Unit) {
        // Use an actual supported navigation stack so secondary pages retain their parent tab.
        val routes = when (route) {
            "account", "permissions", "diagnostics", "ride-preview" -> listOf("home", "settings", route)
            "music-apps" -> listOf("home", "customize", route)
            else -> listOf("home", route)
        }
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                OpenRideSyncTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(390.dp, 844.dp).background(MaterialTheme.colorScheme.background)
                        .testTag(VIEWPORT), propagateMinConstraints = true) {
                        MainTabShell(routes, {}) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (MainTab.visible(route)) {
            compose.onNodeWithTag("main_tab_${MainTab.selected(routes).route}").assertIsSelected()
        } else compose.onNodeWithTag("main_tab_shell").assertDoesNotExist()
    }

    private fun capture(state: String, scrollIndex: Int? = null, scrollMatcher: SemanticsMatcher? = null) {
        val names = listOf("account-unconfigured", "account-signed-out", "permissions-top", "garage-local-empty",
            "garage-add-catalog-empty", "garage-add-catalog", "garage-model-series", "garage-model-year", "garage-detail",
            "media-permission", "media-empty", "media-track-control", "weather-idle", "weather-data", "weather-error",
            "trips-list", "trips-detail", "trips-error", "ride-preparation", "ride-permission-pending",
            "ride-status-connected", "ride-status-error", "diagnostics-unconfigured", "media-selector",
            "favorite-music-empty", "favorite-music-selection", "favorite-music-error",
            "ride-preview-controls", "ride-preview-content", "ride-preview-hu-controls", "diagnostics-capacity", "ride-favorite-recovery",
            "messages-inbox", "messages-reply-editor", "messages-reply-confirm", "calls-incoming-ongoing", "calls-permission",
            "ride-status-report-menu", "ride-status-report-speaking", "ride-status-report-failed")
        val number = names.indexOf(state).also { check(it >= 0) } + 9
        var page = 0
        while (true) {
            captureFrame("${number.toString().padStart(2, '0')}-synthetic-$state-${page.toString().padStart(2, '0')}")
            val matcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange).let {
                if (scrollMatcher == null) it else it and scrollMatcher
            }
            val scroll = if (scrollIndex == null) compose.onNode(matcher) else compose.onAllNodes(matcher)[scrollIndex]
            val axis = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val before = axis.value()
            if (before >= axis.maxValue() - 0.5f) break
            check(page < 30) { "Gallery must reach actual page bottom" }
            // Preview uses bounded independent panels. Base overlap on the
            // selected panel's clipped visible height, not the full screenshot height.
            val visibleBounds = scroll.fetchSemanticsNode().boundsInRoot
            val viewportBounds = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
            val visibleHeight = minOf(visibleBounds.bottom, viewportBounds.bottom) -
                maxOf(visibleBounds.top, viewportBounds.top)
            check(visibleHeight.isFinite() && visibleHeight > 0f) {
                "Selected scroll container must have positive visible height for $state: $visibleBounds"
            }
            val scrollStep = minOf(650f, visibleHeight * 0.75f)
            check(scrollStep.isFinite() && scrollStep > 0f)
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scrollStep) }
            compose.waitForIdle()
            val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            check(after > before) { "Gallery scroll did not advance for $state" }
            page++
        }
    }

    private fun captureFrame(state: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertEquals(390f, bounds.width, 0.1f)
        assertEquals(844f, bounds.height, 0.1f)
        val bitmap = compose.runOnIdle {
            assertTrue("Viewport must fit host horizontally: $bounds in ${hostView.width}",
                bounds.left >= 0 && bounds.right <= hostView.width)
            assertTrue("Viewport must fit host vertically: $bounds in ${hostView.height}",
                bounds.top >= 0 && bounds.bottom <= hostView.height)
            Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.translate(-bounds.left, -bounds.top)
                hostView.draw(canvas)
            }
        }
        try {
            val first = bitmap.getPixel(0, 0)
            var variation = false
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val pixel = bitmap.getPixel(x, y)
                assertEquals("Viewport must not contain transparent/clipped pixels ($x,$y)", 255, android.graphics.Color.alpha(pixel))
                variation = variation || pixel != first
            }
            assertTrue("Production UI must not render a blank rectangle", variation)
            val dir = File(System.getProperty("uiGalleryOutputDir") ?: "build/reports/ui-gallery")
            check(dir.isDirectory || dir.mkdirs())
            File(dir, "$state.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(dir, "$state.fixture.txt").writeText("fixture=synthetic; current production content and MainTabShell navigation policy\n" +
                "profile=390x844; density=1; fontScale=1.0; appearance=LIGHT\n" +
                "testSource=app/src/test/java/org/openridesync/app/ui/FeatureScreenGalleryTest.kt\n" +
                "buildSourceCommit=${org.openridesync.app.BuildConfig.SOURCE_COMMIT}; build-time identifier only\n" +
                "sourceVersion=${System.getProperty("uiGallerySourceVersion") ?: "not supplied; see gallery build manifest"}\n")
        } finally { bitmap.recycle() }
    }

    private companion object { const val VIEWPORT = "feature_gallery_viewport" }
}
