package org.openridesync.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.app.BuildConfig
import org.openridesync.app.media.*
import org.openridesync.app.navigation.MainTab
import org.openridesync.app.runtime.*
import org.openridesync.app.trips.*
import org.openridesync.app.ui.theme.AppearanceMode
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.openridesync.app.vehicles.*
import org.openridesync.app.weather.*
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.wire.HondaWire.*
import org.openridesync.honda.wire.WeatherWire
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Current five-tab production content, with visibly synthetic facts and no live service owner.
 * Indices 38+ extend the existing 01-37 case identities. Each scroll segment is a real native
 * Canvas rendering, not a mockup, golden comparison, hardware/GPU capture or device acceptance.
 * Empty model image IDs deliberately take VehicleImage's no-account/no-cache/no-network branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedesignGalleryTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var hostView: View
    private lateinit var originalLocale: Locale
    private lateinit var originalZone: TimeZone
    private lateinit var profile: Profile
    private lateinit var selectedTab: MainTab
    private val imageRequests = VehicleImageRequests()
    private val actions = mutableListOf<String>()
    private val signedIn = ShellStatus(true, "合成帳號狀態；沒有真實帳號資料", "尚未連線（合成狀態）")

    @Before fun isolateSyntheticFacts() {
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

    @After fun releaseFixture() {
        imageRequests.close()
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalZone)
    }

    @Test fun homeUnread() {
        fixture(MainTab.HOME) { HomeOverviewContent(signedIn, HomeOverview(), onOpen = actions::add) }
        compose.onNodeWithText("尚未查詢").assertExists()
        compose.onAllNodesWithTag("home_tile_pair").assertCountEquals(2)
        sweep(38, "home-unread", "home_screen")
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun homeObservedSyntheticFacts() {
        val garage = garageState(3)
        val reading = WeatherReading(26f, 801, "合成地點（非即時天氣）", "合成預報：晴時多雲", "Clouds", true)
        val observed = homeOverview(signedIn,
            weather = HomeWeatherState(signedIn = true, locationGranted = true,
                current = CurrentWeather(WeatherWire.Weather.getDefaultInstance(), reading), fetchedAtMillis = 1792108800000L),
            media = MediaPageState(signedIn = true, observing = true,
                media = MediaState(access = MediaAccess.GRANTED, selectedId = "synthetic-player",
                    title = "合成測試曲目", artist = "合成作者", playback = MediaPlayback.PAUSED)),
            trips = historyState(), vehicles = garage)
        fixture(MainTab.HOME) { HomeOverviewContent(signedIn, observed, actions::add) }
        compose.onNodeWithText("合成測試曲目").assertExists()
        compose.onNodeWithText("帳號當日合計，非此車里程", substring = true).assertDoesNotExist()
        compose.onNodeWithText(observed.tripDate).assertExists()
        sweep(39, "home-observed-facts", "home_screen")
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun homeSmallDarkLongSignedOut() {
        val status = ShellStatus(false, LONG_ACCOUNT, "尚未連線；合成長狀態不代表已與任何實車配對或連線。")
        fixture(MainTab.HOME, SMALL_DARK) { HomeOverviewContent(status, homeOverview(status), actions::add) }
        compose.onNodeWithText(LONG_ACCOUNT).assertDoesNotExist()
        compose.onNodeWithText("尚未登入").assertExists()
        compose.onAllNodesWithTag("home_tile_pair").assertCountEquals(0)
        sweep(40, "home-signedout-long", "home_screen")
        compose.onNodeWithTag("home_weather").performScrollTo().performClick()
        compose.onNodeWithTag("home_diagnostics").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("account", "diagnostics"), actions) }
    }

    @Test fun tripsMonthlyHistory() {
        trips(historyState())
        sweep(41, "trips-monthly-history", "trips_list")
    }

    @Test fun tripsExpandedDay() {
        trips(historyState(expanded = true))
        sweep(42, "trips-expanded-day", "trips_list")
    }

    @Test fun tripsSchematicRoute() {
        trips(historyState(expanded = true))
        openSyntheticRoute()
        // The canvas is inside a lazy item and may be below the initial viewport.
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_route_canvas"))
        compose.onNodeWithTag("trip_route_canvas").assertExists()
        resetScroll("trips_list")
        sweep(43, "trips-synthetic-route", "trips_list")
    }

    @Test fun tripsMissingRoute() {
        trips(historyState(expanded = true, geometry = false))
        openSyntheticRoute()
        compose.onNodeWithText("路線資料缺少或無效，無法繪製。").assertExists()
        sweep(44, "trips-missing-route", "trips_list")
    }

    @Test fun tripsSmallDarkExpandedLongName() {
        trips(historyState(expanded = true, longName = true), SMALL_DARK)
        sweep(45, "trips-expanded-long", "trips_list")
    }

    @Test fun garageEmpty() {
        garage(VehicleState(garageLoaded = true))
        compose.onNodeWithTag("garage_primary_empty").assertExists()
        sweep(46, "garage-empty", "vehicle_list")
    }

    @Test fun garageMultipleVehicles() {
        garage(garageState(5))
        compose.onNodeWithTag("garage_primary").assertExists()
        sweep(47, "garage-five-vehicles-grid", "vehicle_list")
    }

    @Test fun garageSingleVehicle() {
        garage(garageState(1))
        compose.onNodeWithTag("garage_primary").assertExists()
        compose.onAllNodesWithTag("garage_vehicle_tile").assertCountEquals(0)
        sweep(48, "garage-single-vehicle", "vehicle_list")
    }

    @Test fun garageSmallDarkLongNames() {
        garage(garageState(3, longName = true), SMALL_DARK)
        sweep(49, "garage-long-names", "vehicle_list")
    }

    @Test fun garageExactSavedDetails() {
        val state = garageState(3)
        val vehicle = state.pairedVehicles.first()
        garage(state.copy(detail = vehicle.model, detailYear = vehicle.year, detailVehicleMac = vehicle.mac))
        compose.onNodeWithTag("garage_saved_details").assertExists()
        compose.onNodeWithText("藍牙位址：${vehicle.mac}").assertExists()
        sweep(50, "garage-saved-details", "vehicle_list")
    }

    @Test fun customizationDefaults() {
        customization(DashboardCustomizationState())
        sweep(51, "customization-defaults", "dashboard_customization_screen")
    }

    @Test fun customizationChangedPreferences() {
        customization(DashboardCustomizationState(order = MainMenuSelection.Item.entries.reversed(),
            speechMode = WeatherSpeechMode.SUMMARY, autoReturn = true, musicPackage = "synthetic.invalid.player"))
        compose.onNodeWithTag("weather_speech_SUMMARY").assertIsSelected()
        compose.onNodeWithTag("customization_auto_return").assertIsOn()
        sweep(52, "customization-synthetic-preferences", "dashboard_customization_screen")
    }

    @Test fun customizationSmallDark() {
        customization(DashboardCustomizationState(speechMode = WeatherSpeechMode.OFF), SMALL_DARK)
        sweep(53, "customization-large-text", "dashboard_customization_screen")
    }

    @Test fun settingsHub() {
        settings()
        sweep(54, "settings-hub", "settings_screen")
    }

    @Test fun settingsSmallDarkLongStatus() {
        settings(SMALL_DARK, LONG_ACCOUNT)
        compose.onNodeWithTag("settings_account_state").assertTextEquals(LONG_ACCOUNT)
        sweep(55, "settings-long-status", "settings_screen")
    }

    @Test fun appearanceSelectionUsesRealTheme() {
        var mode by mutableStateOf(AppearanceMode.LIGHT)
        fixture(MainTab.SETTINGS, dark = { mode.resolveDark(false) }) {
            SettingsHubContent(actions::add, {}, mode, { mode = it }, accountMessage = "合成帳號狀態", developer = false)
        }
        compose.onNodeWithTag("settings_appearance_light").performScrollTo().assertIsSelected()
        captureFrame(56, "appearance-light-section", 0, "LIGHT")
        compose.onNodeWithTag("settings_appearance_dark").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppearanceMode.DARK, mode) }
        captureFrame(57, "appearance-dark-section", 0, "DARK")
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    @Test fun settingsAboutDocument() = settingsDocument(58, "about")
    @Test fun settingsPrivacyDocument() = settingsDocument(59, "privacy")
    @Test fun settingsLicensesDocument() = settingsDocument(60, "licenses")
    @Test fun settingsTermsDocument() = settingsDocument(61, "terms")
    @Test fun settingsHelpDocument() = settingsDocument(62, "docs")

    @Test fun homeDeferredGuideFirstPairHint() {
        val garage = VehicleState(garageLoaded = true)
        var dismissed by mutableStateOf(false)
        fixture(MainTab.HOME) {
            val eligible = firstPairHintEligible(deferredGuide = true, signedIn = true,
                garageLoaded = garage.garageLoaded, garageLoading = false, garageError = false,
                pairedCount = 0, dismissed = dismissed)
            HomeOverviewContent(signedIn, homeOverview(signedIn, vehicles = garage), actions::add,
                showFirstPairHint = eligible, onDismissFirstPairHint = { dismissed = true })
        }
        compose.onNodeWithTag("home_first_pair_hint").assertExists()
        sweep(63, "home-deferred-guide-first-pair", "home_screen")
        compose.onNodeWithTag("home_first_pair_open").performScrollTo().performClick()
        compose.onNodeWithTag("home_first_pair_dismiss").performScrollTo().performClick()
        compose.onNodeWithTag("home_first_pair_hint").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("vehicles"), actions); assertTrue(dismissed) }
    }

    private fun settingsDocument(index: Int, id: String) {
        settings()
        compose.onNodeWithTag("settings_$id").performScrollTo().performClick()
        compose.onNodeWithTag("settings_document_$id").assertExists()
        sweep(index, "settings-document-$id", "settings_document")
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
    }

    private fun trips(state: TripState, size: Profile = PHONE) = fixture(MainTab.TRIPS, size) {
        TripHistoryContent(state, true, {}, {}, {}, {}, {}, {})
    }

    private fun openSyntheticRoute() {
        compose.onNodeWithTag("trips_list").performScrollToNode(hasTestTag("trip_route_synthetic-route"))
        compose.onNodeWithTag("trip_route_synthetic-route").performClick()
        compose.onNodeWithText("路線詳情").assertExists()
    }

    private fun garage(state: VehicleState, size: Profile = PHONE) = fixture(MainTab.GARAGE, size) {
        VehicleCatalogContent(state, true, imageRequests = imageRequests)
    }

    private fun customization(state: DashboardCustomizationState, size: Profile = PHONE) = fixture(MainTab.CUSTOMIZE, size) {
        DashboardCustomizationContent(state, { _, _ -> }, {}, {}, {}, {})
    }

    private fun settings(size: Profile = PHONE, message: String = "合成帳號狀態；尚未查詢服務") = fixture(MainTab.SETTINGS, size) {
        SettingsHubContent(actions::add, {}, if (size.dark) AppearanceMode.DARK else AppearanceMode.LIGHT,
            {}, accountMessage = message, developer = true)
    }

    private fun fixture(tab: MainTab, size: Profile = PHONE, dark: () -> Boolean = { size.dark },
        content: @Composable () -> Unit) {
        profile = size
        selectedTab = tab
        compose.setContent {
            val view = LocalView.current
            SideEffect { hostView = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, size.fontScale)) {
                OpenRideSyncTheme(darkTheme = dark()) {
                    Box(Modifier.requiredSize(size.width.dp, size.height.dp).background(MaterialTheme.colorScheme.background)
                        .testTag(VIEWPORT), propagateMinConstraints = true) {
                        val routes = if (tab == MainTab.HOME) listOf("home") else listOf("home", tab.route)
                        MainTabShell(routes, actions::add) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTabs()
    }

    private fun assertTabs() {
        MainTab.entries.forEach { tab ->
            val node = compose.onNodeWithTag("main_tab_${tab.route}").assertIsDisplayed().assertHasClickAction()
            if (tab == selectedTab) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    /** Every scroll has overlapping frames and must terminate at the real bottom. */
    private fun sweep(index: Int, state: String, scrollTag: String) {
        var page = 0
        while (true) {
            captureFrame(index, state, page)
            val scroll = compose.onNodeWithTag(scrollTag)
            val axis = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val before = axis.value()
            if (before >= axis.maxValue() - 0.5f) break
            check(page < 100) { "Gallery did not reach actual bottom for $state; never silently truncate" }
            val bounds = scroll.fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
            val visibleHeight = minOf(bounds.bottom, viewport.bottom) - maxOf(bounds.top, viewport.top)
            check(visibleHeight.isFinite() && visibleHeight > 0f) { "Scroll container has no visible area: $state" }
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, minOf(650f, visibleHeight * 0.75f)) }
            compose.waitForIdle()
            val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            check(after > before) { "Gallery scroll failed to advance: $state" }
            page++
        }
        assertTabs()
    }

    private fun resetScroll(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.ScrollToIndex) { it(0) }
        compose.waitForIdle()
    }

    private fun captureFrame(index: Int, state: String, page: Int, appearance: String = if (profile.dark) "DARK" else "LIGHT") {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertEquals(profile.width.toFloat(), bounds.width, 0.1f)
        assertEquals(profile.height.toFloat(), bounds.height, 0.1f)
        val bitmap = compose.runOnIdle {
            check(hostView.isLaidOut && hostView.width > 0 && hostView.height > 0)
            check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= hostView.width && bounds.bottom <= hostView.height)
            Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { translate(-bounds.left, -bounds.top); hostView.draw(this) }
            }
        }
        try {
            val directory = File(System.getProperty("uiGalleryOutputDir") ?: "build/reports/ui-gallery")
            check(directory.isDirectory || directory.mkdirs())
            val prefix = "${index.toString().padStart(2, '0')}-redesign-synthetic-$state-${profile.label}"
            File(directory, "$prefix-${page.toString().padStart(2, '0')}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(directory, "$prefix.fixture.txt").writeText(
                "fixture=synthetic; no account, device, private image or live service\n" +
                "renderer=production MainTabShell + production content; native View.draw(Canvas)\n" +
                "profile=${profile.width}x${profile.height}; density=1; fontScale=${profile.fontScale}; appearance=$appearance\n" +
                "selectedTab=${selectedTab.route}\n" +
                "buildSourceCommit=${BuildConfig.SOURCE_COMMIT}; build-time identifier, not proof of a clean working tree\n" +
                "sourceVersion=${System.getProperty("uiGallerySourceVersion") ?: "not supplied; see gallery build manifest"}\n" +
                "testSource=app/src/test/java/org/openridesync/app/ui/RedesignGalleryTest.kt\n")
            val first = bitmap.getPixel(0, 0)
            var variation = false
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                val pixel = bitmap.getPixel(x, y)
                assertEquals("Transparent/clipped viewport pixel ($x,$y) in $state", 255, android.graphics.Color.alpha(pixel))
                variation = variation || pixel != first
            }
            assertTrue("Production UI rendered a blank rectangle: $state", variation)
        } finally { bitmap.recycle() }
    }

    /** Persist only explicitly fictional records into this test's disposable directory. */
    private fun garageState(count: Int, longName: Boolean = false): VehicleState {
        val store = PairedVehicleStore(temporary.newFolder())
        repeat(count) { index ->
            store.upsert("02:00:00:00:00:${(index + 1).toString().padStart(2, '0')}",
                "SYNTHETIC-DEVICE-${index + 1}", "synthetic-series", "synthetic-firmware",
                "合成測試車輛 ${index + 1}" + if (longName) "：用來檢查長名稱與大字排版，沒有真實配對裝置" else "",
                Model.newBuilder().setModelLabel("合成測試車款 ${index + 1}").setModelSeriesLabel("synthetic-series")
                    .addModelYears(2026).addModelYears(2025).setDisplacement(125)
                    .setMeter(Meter.newBuilder().setGeneration(1)).build(),
                2026, PairedVehicleStore.State.PAIRED)
        }
        return VehicleState(garageLoaded = true, pairedVehicles = store.read())
    }

    private fun historyState(expanded: Boolean = false, geometry: Boolean = true, longName: Boolean = false): TripState {
        val days = listOf(0, 1, 32).map { index ->
            DailyTrips.newBuilder().setTripDate(1792108800L - index * 86400L)
                .setStartTime(1792137600L - index * 86400L).setEndTime(1792139400L - index * 86400L)
                .addTripUuids(if (index == 0) "synthetic-route" else "synthetic-route-$index")
                .setTotalDistance(12000f).setTotalTime(1800L).setAvgSpeed(6.67f)
                .addVehicleDisplayName("合成測試車輛" + if (longName) "：非真實旅程，只檢查長名稱換行及路線內容可到達性" else "").build()
        }
        val detail = TripDetail.newBuilder().setTripUuid("synthetic-route").setTotalDistance(12000f).setAvgSpeed(6.67f)
        if (geometry) listOf(0.0 to 0.0, 0.01 to 0.005, 0.005 to 0.012, 0.02 to 0.018).forEachIndexed { index, (lat, lon) ->
            detail.addTripLocations(TripLocation.newBuilder().setTimestamp(1792137600L + index * 600L)
                .setCoordinates(Coord.newBuilder().setLat(lat).setLon(lon)).setIsCoarse(true))
        }
        return TripState(hasLoaded = true, days = days, hasMore = true,
            summary = TripsSummary.newBuilder().setTotalDistance(84000f).setTotalTime(10800L).setTotalDriveDay(8).build(),
            selectedDay = days.first().takeIf { expanded },
            details = if (expanded) ListTripDetailsResponse.newBuilder().setTotalDistance(12000f).setTotalTime(1800L)
                .setAvgSpeed(6.67f).addDailyTripDetails(detail).build() else null)
    }

    private data class Profile(val width: Int, val height: Int, val fontScale: Float, val dark: Boolean, val label: String)
    private companion object {
        const val VIEWPORT = "redesign_gallery_viewport"
        val PHONE = Profile(390, 844, 1f, false, "phone390x844-font1")
        val SMALL_DARK = Profile(320, 480, 2f, true, "small320x480-font2-dark")
        const val LONG_ACCOUNT = "尚未登入。這是合成的長帳號說明，用來檢查小螢幕與兩倍字體；沒有真實帳號，也沒有背景登入、資料讀取或車輛連線。"
    }
}
