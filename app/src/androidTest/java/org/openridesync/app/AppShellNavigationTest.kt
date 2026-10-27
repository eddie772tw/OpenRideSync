package org.openridesync.app

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthState
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.navigation.AppRoutes
import org.openridesync.app.runtime.RideStage
import org.openridesync.app.ui.AccountScreen
import org.openridesync.app.ui.HomeScreen
import org.openridesync.app.ui.SettingsScreen
import org.openridesync.app.ui.SetupScreen
import org.openridesync.app.ui.ShellStatus

/**
 * Requires a clean, dedicated instrumentation target built with -PwithHondaConfig=false.
 * Application startup precedes these guards. Test actions never request permissions or connect.
 */
class AppShellNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun withProgress(progress: String?, body: () -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The target Application has already initialized AuthSession and RideConnection.
        // This guard stops test actions; it cannot prevent or undo Application startup.
        assumeCleanInstrumentationFixture()
        val preferences = context.getSharedPreferences("introduction", Context.MODE_PRIVATE)
        val old = preferences.getString("progress_v1", null)
        val oldStep = preferences.getString("step_v1", null)
        preferences.edit().putString("progress_v1", progress).putString("step_v1", "0").commit()
        try { body() } finally { preferences.edit().putString("progress_v1", old).putString("step_v1", oldStep).commit() }
    }

    private fun systemBack(activity: ActivityScenario<MainActivity>) {
        activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun firstRunCompletesAllGuideStagesAndCanReopenDeferredSummary() = withProgress(null) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("welcome_begin").performScrollTo().performClick()
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 1／3 · 帳號")
            compose.onNodeWithTag("welcome_screen").assertDoesNotExist()
            compose.onNodeWithTag("setup_next").performScrollTo().performClick()
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 2／3 · 權限與車輛")
            compose.onNodeWithTag("setup_next").performScrollTo().performClick()
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 3／3 · 開始探索")
            activity.recreate()
            compose.onNodeWithTag("setup_explore").performScrollTo().performClick()
            compose.onNodeWithTag("home_screen").assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("home_screen").assertExists()
            compose.onNodeWithTag("home_setup").performScrollTo().performClick()
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 3／3 · 開始探索")
            compose.onNodeWithTag("setup_explore").assertExists()
        }
    }

    @Test fun systemBackDismissesWelcomeForThisActivityButDoesNotRecordAnExplicitSkip() = withProgress(null) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("welcome_screen").assertExists()
            systemBack(activity)
            compose.onNodeWithTag("home_screen").assertExists()
            activity.recreate()
            compose.onNodeWithTag("home_screen").assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("welcome_screen").assertExists()
            compose.onNodeWithTag("setup_screen").assertDoesNotExist()
        }
    }

    @Test fun skippedFirstRunCanExploreEveryAccountGateAndLocalScreensWithoutStartingARide() = withProgress(null) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("welcome_skip").performScrollTo().performClick()
            for (feature in listOf("vehicles", "media", "weather", "trips")) {
                compose.onNodeWithTag("home_$feature").performScrollTo().performClick()
                compose.onNodeWithTag("account_login").assertIsNotEnabled()
                compose.onNodeWithTag("account_read").assertDoesNotExist()
                systemBack(activity)
                compose.onNodeWithTag("home_screen").assertExists()
            }
            compose.onNodeWithTag("home_ride").performScrollTo().performClick()
            compose.onNodeWithTag("ride_idle").assertExists()
            compose.onNodeWithTag("ride_permissions").performScrollTo().performClick()
            compose.onNodeWithTag("permission_PAIRING_status").assertExists()
            systemBack(activity)
            compose.onNodeWithTag("ride_idle").assertExists()
            compose.onNodeWithTag("ride_diagnostics").performScrollTo().performClick()
            activity.recreate()
            systemBack(activity)
            compose.onNodeWithTag("ride_idle").assertExists()
            systemBack(activity)
            compose.onNodeWithTag("home_screen").assertExists()
            compose.onNodeWithTag("home_diagnostics").performScrollTo().performClick()
            compose.onNodeWithTag("diagnostics_back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("home_screen").assertExists()
            activity.onActivity {
                assertEquals(AuthStatus.UNAVAILABLE, AuthSession.get().state.value.status)
                assertEquals(RideStage.IDLE, (it.application as OpenRideSyncApplication).ride.state.value.stage)
            }
        }
    }

    @Test fun deferringGuideFromSettingsClearsOldOwnersAndSystemBackWalksGuideStages() = withProgress("deferred") {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("home_settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings_setup").performScrollTo().performClick()
            compose.onNodeWithTag("setup_next").performScrollTo().performClick()
            compose.onNodeWithTag("setup_home").performScrollTo().performClick()
            compose.onNodeWithTag("home_settings").performScrollTo().performClick()
            systemBack(activity)
            compose.onNodeWithTag("home_screen").assertExists()
            compose.onNodeWithTag("home_setup").performScrollTo().performClick()
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 2／3 · 權限與車輛")
            compose.onNodeWithTag("setup_next").performScrollTo().performClick()
            activity.recreate()
            systemBack(activity)
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 2／3 · 權限與車輛")
            systemBack(activity)
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 1／3 · 帳號")
            systemBack(activity)
            compose.onNodeWithTag("home_screen").assertExists()
            compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        }
    }

    @Test fun repeatedRideIntentsDuringGuideReturnToTheGuideWithOneBack() = withProgress("started") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val openRide = Intent(context, MainActivity::class.java).setAction(MainActivity.OPEN_RIDE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ActivityScenario.launch<MainActivity>(openRide).use { activity ->
            compose.onNodeWithTag("ride_idle").assertExists()
            repeat(2) {
                compose.onNodeWithTag("ride_diagnostics").performScrollTo().performClick()
                activity.onActivity { it.startActivity(openRide) }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("ride_idle").fetchSemanticsNodes().isNotEmpty() }
            }
            activity.recreate()
            systemBack(activity)
            compose.onNodeWithTag("setup_step").assertTextEquals("步驟 1／3 · 帳號")
            compose.onNodeWithTag("ride_idle").assertDoesNotExist()
            systemBack(activity)
            compose.onNodeWithTag("home_screen").assertExists()
        }
    }

    @Test fun skipRecreateAndRelaunchKeepHomeWhileSettingsReturnIsStable() = withProgress(null) {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("welcome_skip").performScrollTo().performClick()
            compose.onNodeWithTag("home_screen").assertExists()
            activity.recreate()
            compose.onNodeWithTag("home_screen").assertExists()
            compose.onNodeWithTag("home_settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings_account").performScrollTo().performClick()
            activity.recreate()
            compose.onNodeWithTag("account_back").performScrollTo().performClick()
            compose.onNodeWithTag("settings_screen").assertExists()
            compose.onNodeWithTag("shell_back").performScrollTo().performClick()
            compose.onNodeWithTag("home_screen").assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("home_screen").assertExists()
            compose.onNodeWithTag("welcome_screen").assertDoesNotExist()
        }
    }

    @Test fun startedGuideResumesAndAccountReturnsToGuide() = withProgress("started") {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("setup_screen").assertExists()
            compose.onNodeWithTag("setup_account").performScrollTo().performClick()
            activity.recreate()
            compose.onNodeWithTag("account_back").performScrollTo().performClick()
            compose.onNodeWithTag("setup_screen").assertExists()
            compose.onNodeWithTag("setup_home").performScrollTo().performClick()
            compose.onNodeWithTag("home_screen").assertExists()
        }
    }
    @Test fun guidePositionSurvivesFeatureReturnAndColdRelaunch() = withProgress("started") {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithTag("setup_next").performScrollTo().performClick()
            compose.onNodeWithTag("setup_permissions").performScrollTo().performClick()
            activity.recreate()
            compose.onNodeWithText("返回").performScrollTo().performClick()
            compose.onNodeWithTag("setup_permissions").assertExists()
            compose.onNodeWithTag("setup_next").performScrollTo().performClick()
            compose.onNodeWithTag("setup_explore").assertExists()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("setup_explore").assertExists()
            compose.onNodeWithTag("shell_back").performScrollTo().performClick()
            compose.onNodeWithTag("setup_permissions").assertExists()
        }
    }

    @Test fun guideAccountDiagnosticsReturnToOriginalGuideStage() = withProgress("started") {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("setup_account").performScrollTo().performClick()
            compose.onNodeWithTag("account_diagnostics").performScrollTo().performClick()
            compose.onNodeWithTag("diagnostics_back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("account_back").performScrollTo().performClick()
            compose.onNodeWithTag("setup_account").assertExists()
            compose.onNodeWithTag("setup_next").assertExists()
        }
    }
    @Test fun settingsAccountDiagnosticsReturnToSettings() = withProgress("deferred") {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("home_settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings_account").performScrollTo().performClick()
            compose.onNodeWithTag("account_diagnostics").performScrollTo().performClick()
            compose.onNodeWithTag("diagnostics_back").assertIsDisplayed().performClick()
            compose.onNodeWithTag("account_back").performScrollTo().performClick()
            compose.onNodeWithTag("settings_screen").assertExists()
        }
    }
}

/**
 * Synthetic recovery journeys: production composables and AppRoutes, controlled auth/read facts.
 * Requires a clean, dedicated instrumentation target built with -PwithHondaConfig=false.
 * createComposeRule still starts the target Application, which initializes AuthSession and
 * RideConnection before @Before. The guard cannot prevent that startup. The test body uses a
 * synthetic screen owner instead of MainActivity, and its callbacks do not invoke auth or RPC.
 * StateRestorationTester exercises Compose saved state, not Android process death.
 */
class SyntheticAccountRecoveryJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Before fun requireCleanFixtureBeforeTestActions() = assumeCleanInstrumentationFixture()

    @Test fun failedLoginRequiresExplicitRetryAndRestorationDoesNotRestartIt() {
        val auth = mutableStateOf(AuthState(AuthStatus.SIGNED_OUT))
        var loginRequests = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RecoveryJourney(auth.value, AccountState(), "started", onLogin = {
                loginRequests++
                auth.value = AuthState(AuthStatus.SIGNING_IN)
            })
        }
        compose.onNodeWithTag("setup_account").performScrollTo().performClick()
        compose.onNodeWithTag("account_login").performScrollTo().performClick()
        compose.onNodeWithTag("account_login").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, loginRequests)
            auth.value = AuthState(AuthStatus.ERROR, "SYNTHETIC_NETWORK_FAILURE")
        }
        compose.onNodeWithText("登入未完成，請稍後再試或查看診斷資訊。").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("account_login").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, loginRequests) }
        compose.onNodeWithTag("account_login").performClick()
        compose.onNodeWithTag("account_login").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, loginRequests)
            // A cancelled external login returns signed out; it must not finish the guide.
            auth.value = AuthState(AuthStatus.SIGNED_OUT, "CANCELLED")
        }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.onNodeWithTag("setup_step").assertTextEquals("步驟 1／3 · 帳號")
        compose.onNodeWithTag("account_login").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, loginRequests) }
    }

    @Test fun failedAccountReadRetryCanCancelAndReturnToSettingsAfterRestore() {
        val account = mutableStateOf(AccountState())
        var reads = 0
        var cancellations = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RecoveryJourney(AuthState(AuthStatus.SIGNED_IN), account.value, "deferred", onRead = {
                reads++
                account.value = AccountState(busy = true)
            }, onCancel = {
                cancellations++
                account.value = AccountState(cancelled = true)
            })
        }
        compose.onNodeWithTag("home_settings").performScrollTo().performClick()
        compose.onNodeWithTag("settings_account").performScrollTo().performClick()
        compose.onNodeWithTag("account_read").performScrollTo().performClick()
        compose.onNodeWithTag("account_read").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, reads)
            account.value = AccountState(errorCode = "NETWORK")
        }
        compose.onNodeWithText("暫時無法讀取 Honda 帳號，請稍後再試。").assertExists()
        compose.onNodeWithTag("account_read").performScrollTo().performClick()
        compose.onNodeWithText("取消帳號讀取").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("已取消更新。可隨時重新讀取帳號資訊。").assertExists()
        compose.onNodeWithText("尚未取得").assertExists()
        compose.onNodeWithTag("account_read").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, reads); assertEquals(1, cancellations) }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("shell_back").performScrollTo().performClick()
        compose.onNodeWithTag("home_screen").assertExists()
        compose.runOnIdle { assertEquals(2, reads); assertEquals(1, cancellations) }
    }
}

/** A post-startup check, not a substitute for preparing an unconfigured, clean test target. */
private fun assumeCleanInstrumentationFixture() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    assumeTrue("Requires a clean test APK built with -PwithHondaConfig=false; auth asset must be absent",
        "auth-client.json" !in context.assets.list("").orEmpty())
    assumeTrue("Requires unconfigured auth; never sign out or alter a real session for a test",
        AuthSession.get().state.value.status == AuthStatus.UNAVAILABLE && AuthSession.get().currentUser() == null)
    assumeTrue("Requires an idle application; never disconnect a user's ride for a test",
        (context.applicationContext as OpenRideSyncApplication).ride.state.value.stage == RideStage.IDLE)
}

/** Test-only screen owner. Real Activity wiring is exercised by AppShellNavigationTest above. */
@Composable
private fun RecoveryJourney(auth: AuthState, account: AccountState, progress: String,
    onLogin: () -> Unit = {}, onRead: () -> Unit = {}, onCancel: () -> Unit = {}) {
    var savedRoutes by rememberSaveable(stateSaver = listSaver<List<String>, String>(
        save = { it }, restore = { it.toList() })) { mutableStateOf(AppRoutes.initial(progress)) }
    var guideStep by rememberSaveable { mutableIntStateOf(0) }
    val routes = AppRoutes.restore(savedRoutes)
    val open: (String) -> Unit = { savedRoutes = AppRoutes.open(routes, it) }
    val back: () -> Unit = { savedRoutes = AppRoutes.back(routes) }
    val unexpected: () -> Unit = { error("Recovery journey must not perform external actions") }
    val status = ShellStatus(auth.status == AuthStatus.SIGNED_IN, "合成帳號狀態", "未連線")
    BackHandler(enabled = routes.size > 1, onBack = back)
    MaterialTheme {
        when (routes.last()) {
            "home" -> HomeScreen(status, open)
            "setup" -> SetupScreen(status, open, back, initialStep = guideStep, onStepChanged = { guideStep = it })
            "settings" -> SettingsScreen(open, back, developer = false)
            "account" -> AccountScreen(auth, account, null, null,
                onLogin, onRead, onCancel, unexpected, unexpected, unexpected, unexpected, unexpected,
                onBack = back, showFeatureLinks = false)
            else -> error("Unexpected route in synthetic recovery journey: ${routes.last()}")
        }
    }
}
