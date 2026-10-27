package org.openridesync.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import org.openridesync.app.ui.theme.applyAppearanceSystemBars
import androidx.compose.foundation.isSystemInDarkTheme
import org.openridesync.app.ui.theme.rememberAppearancePreferences
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.core.content.edit
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.auth.BrowserAuthActivity
import org.openridesync.app.navigation.AppRoutes
import org.openridesync.app.navigation.MainTab
import org.openridesync.app.ui.MainTabShell
import org.openridesync.app.runtime.DashboardCustomizationScreen
import org.openridesync.app.ui.HomeOverviewRoute
import org.openridesync.app.ui.WelcomeScreen
import org.openridesync.app.ui.SetupScreen
import org.openridesync.app.ui.SettingsScreen
import org.openridesync.app.ui.ShellStatus
import org.openridesync.app.ui.AccountScreen
import org.openridesync.app.ui.DiagnosticsScreen
import org.openridesync.app.ui.PermissionsScreen
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.openridesync.app.vehicles.VehicleCatalogScreen
import org.openridesync.app.trips.TripHistoryScreen
import org.openridesync.app.media.MediaScreen
import org.openridesync.app.messages.MessagesScreen
import org.openridesync.app.calls.CallsScreen
import org.openridesync.app.media.FavoriteMusicScreen
import org.openridesync.app.media.FavoriteStartTestScreen
import org.openridesync.app.runtime.RidePreviewScreen
import org.openridesync.app.runtime.HandlebarTestScreen
import org.openridesync.app.runtime.HandlebarTestLifetime
import org.openridesync.app.weather.WeatherScreen
import org.openridesync.app.weather.WeatherReportTestScreen
import org.openridesync.app.pairing.PairingViewModel
import org.openridesync.app.pairing.PairingLaunchers
import org.openridesync.app.runtime.RideViewModel
import org.openridesync.app.runtime.RideScreen
import org.openridesync.app.runtime.RidePhoneContent
import org.openridesync.app.runtime.RideScreenAwake
import org.openridesync.app.runtime.RideStatusLayout
import org.openridesync.app.runtime.rememberRideDeviceStatus
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.runtime.phoneLocked
import org.openridesync.app.runtime.RideStage
import org.openridesync.app.runtime.RidePermissionLauncher
import org.openridesync.diagnostics.DiagnosticJournal.*

class MainActivity : ComponentActivity() {
    private var openRide by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A saved screen wins on recreation. A new tap on an existing task arrives in onNewIntent.
        openRide = savedInstanceState == null && intent?.action == OPEN_RIDE
        enableEdgeToEdge()
        setContent {
            val account: AccountViewModel = viewModel()
            val accountState by account.state.collectAsStateWithLifecycle()
            val auth by account.session.state.collectAsStateWithLifecycle()
            val pairing: PairingViewModel = viewModel()
            val pairingState by pairing.state.collectAsStateWithLifecycle()
            val ride: RideViewModel = viewModel()
            val ridePermission by ride.permissionRequest.collectAsStateWithLifecycle()
            val rideState by ride.connection.state.collectAsStateWithLifecycle()
            val ridePage by ride.connection.musicPage.collectAsStateWithLifecycle()
            val screenAwake by (application as OpenRideSyncApplication).ridePreferences.screenAwake.collectAsStateWithLifecycle()
            RideScreenAwake(window, rideState.stage == RideStage.CONNECTED, screenAwake)
            val phoneStatus = rememberRideDeviceStatus(rideState.stage == RideStage.CONNECTED)
            val rideWeather by (application as OpenRideSyncApplication).homeWeather.state.collectAsStateWithLifecycle()
            val introduction = getSharedPreferences("introduction", MODE_PRIVATE)
            var savedRoutes by rememberSaveable(stateSaver = listSaver<List<String>, String>(
                save = { it }, restore = { it.toList() })) {
                mutableStateOf(AppRoutes.initial(introduction.getString("progress_v1", null)))
            }
            val routes = AppRoutes.restore(savedRoutes)
            val destination = routes.last()
            HandlebarTestLifetime(BuildConfig.DEBUG && destination == "handlebar-test") {
                ride.connection.stopHandlebarTest()
            }
            fun open(route: String) {
                if (ride.connection.state.value.phoneLocked) return
                if (route == "setup") introduction.edit { putString("progress_v1", "started") }
                if (route == "home" && destination == "setup") introduction.edit { putString("progress_v1", "deferred") }
                savedRoutes = ArrayList(AppRoutes.open(routes, route))
            }
            val back: () -> Unit = {
                if (!ride.connection.state.value.phoneLocked) savedRoutes = ArrayList(AppRoutes.back(routes))
            }
            BackHandler(enabled = routes.size > 1, onBack = back)
            LaunchedEffect(openRide) {
                if (openRide) { savedRoutes = ArrayList(AppRoutes.open(savedRoutes, "ride")); openRide = false }
            }
            // Connection state changes update status only. Garage connect already opens ride
            // explicitly; a retained CONNECTED value must not overwrite restored navigation.
            val shell = ShellStatus(auth.status == AuthStatus.SIGNED_IN, when (auth.status) {
                AuthStatus.UNAVAILABLE -> "此版本未配置登入。可先使用設定導引、權限說明與本機診斷。"
                AuthStatus.SIGNING_IN -> "登入進行中；尚未確認帳號。"
                AuthStatus.ERROR -> "登入未完成，可到帳號頁重試或查看診斷。"
                AuthStatus.SIGNED_IN -> when {
                    accountState.requiresLogin -> "需要重新登入確認 Honda 帳號。"
                    accountState.busy -> "已登入，正在讀取 Honda 帳號。"
                    accountState.errorCode != null -> "已登入，但 Honda 帳號讀取失敗；可至帳號頁重試。"
                    accountState.details == null -> "已登入；Honda 帳號資訊尚未確認。"
                    else -> "已登入；Honda 帳號已讀取，不代表車輛已配對或連線。"
                }
                else -> "尚未登入。可先查看設定導引、權限與診斷。"
            }, when (rideState.stage) {
                RideStage.IDLE -> "未連線"
                RideStage.CONNECTING -> "連線中"
                RideStage.RECONNECTING -> "重新連線中"
                RideStage.CONNECTED -> "已連線"
                RideStage.STOPPING -> "正在中斷連線"
                RideStage.FAILED -> "連線失敗"
            })
            val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            val appearancePreferences = rememberAppearancePreferences()
            val appearance by appearancePreferences.mode.collectAsStateWithLifecycle()
            val darkTheme = appearance.resolveDark(isSystemInDarkTheme())
            SideEffect { applyAppearanceSystemBars(darkTheme) }
            OpenRideSyncTheme(darkTheme = darkTheme) {
                PairingLaunchers(pairing, pairingState)
                RidePermissionLauncher(ride, ridePermission)
                Box(Modifier.fillMaxSize().then(if (rideState.stage == RideStage.CONNECTED)
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    else Modifier)) {
                RideStatusLayout(phoneStatus.takeIf { rideState.stage == RideStage.CONNECTED },
                    if (auth.status == AuthStatus.SIGNED_IN) rideWeather else org.openridesync.app.weather.HomeWeatherState(),
                    onWeather = if (rideState.mode == Gen1Input.Function.MODE_SAB && (destination != "ride" ||
                        ridePage.stage == org.openridesync.app.runtime.RidePageStage.MENU &&
                        org.openridesync.app.runtime.MainMenuSelection.Item.WEATHER in ridePage.menuItems)) ({
                        if (destination == "ride") ride.connection.openFunction(org.openridesync.app.runtime.MainMenuSelection.Item.WEATHER)
                        else open("weather")
                    }) else null,
                    onPermissions = if (rideState.mode == Gen1Input.Function.MODE_SAB) ({ open("permissions") }) else null,
                    weatherSelected = rideState.stage == RideStage.CONNECTED && rideState.mode == Gen1Input.Function.MODE_SAB &&
                        (ridePage.stage == org.openridesync.app.runtime.RidePageStage.WEATHER ||
                            ridePage.stage == org.openridesync.app.runtime.RidePageStage.MENU &&
                            ridePage.menuSelected == org.openridesync.app.runtime.MainMenuSelection.Item.WEATHER),
                    statusSelected = ridePage.statusChoice.takeIf {
                        rideState.stage == RideStage.CONNECTED && rideState.mode == Gen1Input.Function.MODE_SAB &&
                            ridePage.stage in setOf(org.openridesync.app.runtime.RidePageStage.STATUS_MENU,
                                org.openridesync.app.runtime.RidePageStage.STATUS_REPORT)
                    }) {
                RidePhoneContent(rideState, onOpenRide = if (destination == "ride") null else ({ open("ride") }),
                    onRequestSab = { ride.connection.requestRoadSyncMode() }) {
                MainTabShell(routes, onSelect = { route ->
                    if (ride.connection.state.value.phoneLocked) return@MainTabShell
                    // A tab is an explicit departure, unlike Activity recreation or a system picker pause.
                    if (destination == "vehicles") {
                        pairing.clear()
                        androidx.lifecycle.ViewModelProvider(this@MainActivity)[org.openridesync.app.vehicles.VehicleViewModel::class.java].cancel()
                    }
                    savedRoutes = ArrayList(MainTab.select(routes, route))
                }, immersive = destination == "ride" && rideState.stage == RideStage.CONNECTED) {
                when (destination) {
                    "customize" -> DashboardCustomizationScreen(onMusicApps = { open("music-apps") }, onBack = back)
                    "home" -> HomeOverviewRoute(shell, ::open)
                    "welcome" -> WelcomeScreen(onBegin = {
                        introduction.edit { putString("progress_v1", "started"); putString("step_v1", "0") }
                        savedRoutes = arrayListOf("home", "setup")
                    }, onSkip = {
                        introduction.edit { putString("progress_v1", "deferred") }
                        savedRoutes = arrayListOf("home")
                    })
                    "setup" -> SetupScreen(shell, ::open, back,
                        initialStep = introduction.getString("step_v1", "0")?.toIntOrNull() ?: 0,
                        onStepChanged = { introduction.edit { putString("step_v1", it.toString()) } })
                    "settings" -> SettingsScreen(::open, back, BuildConfig.DEBUG, accountMessage = shell.accountMessage)
                    "quick-replies" -> org.openridesync.app.ui.QuickReplySettingsScreen(back,
                        (application as OpenRideSyncApplication).quickReplyPreferences)
                    "recognition-language" -> org.openridesync.app.ui.RecognitionLanguageScreen(back,
                        (application as OpenRideSyncApplication).recognitionLanguages)
                    "trip-recording" -> org.openridesync.app.trips.TripRecordingScreen(onBack = back,
                        onPermissions = { open("permissions") }, onAccount = { open("account") })
                    "permissions" -> PermissionsScreen(onBack = back)
                    "ride-preview" -> RidePreviewScreen(onBack = back,
                        onStartMediaTest = { open("media-start-test") }, onStartWeatherTest = { open("weather-report-test") })
                    "media-start-test" -> FavoriteStartTestScreen(onBack = back)
                    "navigation-observation" -> org.openridesync.app.navigation.NavigationObservationScreen(onBack = back)
                    "weather-report-test" -> WeatherReportTestScreen(onBack = back)
                    "handlebar-test" -> HandlebarTestScreen(onBack = back)
                    "diagnostics" -> DiagnosticsScreen(onBack = back)
                    "vehicles" -> VehicleCatalogScreen(onBack = back, onRide = { open("ride") }, onAccount = { open("account") })
                    "ride" -> RideScreen(onBack = back, onGarage = { open("vehicles") },
                        onMedia = { open("media") }, onDiagnostics = { open("diagnostics") },
                        onPermissions = { open("permissions") }, onNavigation = { open("navigation") }, onCalls = { open("calls") },
                        onTrips = { open("trips") }, onAccount = { open("account") },
                        onQuickReplies = { open("quick-replies") }, onRecognitionLanguage = { open("recognition-language") })
                    "trips" -> TripHistoryScreen(onBack = back, onAccount = { open("account") })
                    "media" -> MediaScreen(onBack = back, onFavorite = { open("music-apps") }, onPermissions = { open("permissions") }, onAccount = { open("account") })
                    "messages" -> MessagesScreen(onBack = back, onAccount = { open("account") })
                    "calls" -> CallsScreen(onBack = back, onAccount = { open("account") })
                    "navigation" -> org.openridesync.app.navigation.NavigationScreen(onBack = back, onAccount = { open("account") })
                    "music-apps" -> FavoriteMusicScreen(onBack = back, onAccount = { open("account") })
                    "weather" -> WeatherScreen(onBack = back, onAccount = { open("account") })
                    else -> {
                        val user = if (auth.status == AuthStatus.SIGNED_IN) account.session.currentUser() else null
                        AccountScreen(auth, accountState, user?.email, user?.uid,
                            onLogin = {
                                account.prepareLogin()
                                login.launch(Intent(this@MainActivity, BrowserAuthActivity::class.java))
                            },
                            onRead = account::readAccount,
                            onCancel = account::cancelRead,
                            onSignOut = account::signOut,
                            onDiagnostics = { open("diagnostics") },
                            onVehicles = { open("vehicles") },
                            onTrips = { open("trips") },
                            onMedia = { open("media") },
                            onRide = { open("ride") },
                            onWeather = { open("weather") },
                            onPermissions = { open("permissions") },
                            onBack = back, showFeatureLinks = false)
                    }
                }
                }
                }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == OPEN_RIDE) openRide = true
    }

    override fun onStart() {
        super.onStart()
        (application as OpenRideSyncApplication).journal?.record(Component.APP, null, Event.START, Outcome.OBSERVED, ErrorCode.NONE)
    }

    override fun onStop() {
        (application as OpenRideSyncApplication).journal?.record(Component.APP, null, Event.STOP, Outcome.OBSERVED, ErrorCode.NONE)
        super.onStop()
    }

    companion object {
        const val OPEN_RIDE = "org.openridesync.app.OPEN_RIDE"
    }
}
