package org.openridesync.app.ui

import androidx.compose.runtime.*
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.media.MediaViewModel
import org.openridesync.app.trips.TripViewModel
import org.openridesync.app.vehicles.VehicleViewModel
import org.openridesync.app.vehicles.VehicleImage
import org.openridesync.app.vehicles.imageId
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.honda.wire.HondaWire.Model

/** Home reads account history while foregrounded; it never starts recording a ride. */
@Composable
internal fun HomeOverviewRoute(status: ShellStatus, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val weather = (context.applicationContext as OpenRideSyncApplication).homeWeather
    val trips: TripViewModel = viewModel(key = "home-trips")
    val vehicles: VehicleViewModel = viewModel(key = "home-vehicles")
    // A distinct observer avoids one page's disposal stopping another page's media owner.
    val media: MediaViewModel = viewModel(key = "home-media")
    val weatherState by weather.state.collectAsStateWithLifecycle()
    val tripState by trips.state.collectAsStateWithLifecycle()
    val vehicleState by vehicles.state.collectAsStateWithLifecycle()
    val mediaState by media.state.collectAsStateWithLifecycle()
    val auth by trips.session.state.collectAsStateWithLifecycle()
    val focused = vehicleState.pairedVehicles.singleOrNull { it.inFocus }
    var foreground by remember { mutableStateOf(false) }
    LaunchedEffect(foreground, auth, status.signedIn, vehicleState.garageLoaded, focused) {
        if (foreground && status.signedIn && vehicleState.garageLoaded && focused == null) vehicles.openCatalog()
    }
    var fallbackModel by remember(auth) { mutableStateOf<Model?>(null) }
    LaunchedEffect(auth, status.signedIn, vehicleState.catalogs) {
        if (fallbackModel == null) fallbackModel = homeBackgroundCandidates(status.signedIn, vehicleState).randomOrNull()
    }
    var vehicleColor by remember(auth, focused, fallbackModel) {
        mutableStateOf<Color?>(null)
    }
    val accountName = if (status.signedIn) trips.session.currentUser()?.let {
        it.displayName?.takeIf(String::isNotBlank) ?: it.email?.takeIf(String::isNotBlank)
    } ?: "帳號名稱未提供" else "尚未登入"
    val introduction = remember(context) { context.getSharedPreferences("introduction", Context.MODE_PRIVATE) }
    var hintDismissed by remember(introduction) { mutableStateOf(introduction.getBoolean("first_pair_hint_dismissed_v1", false)) }
    val showHint = firstPairHintEligible(introduction.getString("progress_v1", null) == "deferred",
        status.signedIn, vehicleState.garageLoaded, vehicleState.garageLoading, vehicleState.garageError,
        vehicleState.pairedVehicles.size, hintDismissed)
    HomeObservationLifecycle(weather::onHomeStart, {
        foreground = true; media.onStart(); trips.onHomeStart(); vehicles.reloadPairedVehicles()
    },
        { foreground = false; media.onStop(); trips.onStop(); vehicles.cancel(); vehicles.imageRequests.reset(); weather.onHomeStop() },
        restartKey = auth)
    HomeOverviewContent(status, homeOverview(status, weatherState, mediaState, tripState, vehicleState), onOpen,
        showFirstPairHint = showHint, onDismissFirstPairHint = {
            introduction.edit { putBoolean("first_pair_hint_dismissed_v1", true) }; hintDismissed = true
        }, onMediaPrevious = media::homePrevious, onMediaToggle = media::homeToggle,
        onMediaNext = media::homeNext, accountName = accountName, vehicleColor = vehicleColor,
        fallbackBackground = {
            if (foreground && status.signedIn) fallbackModel?.let {
                VehicleImage(imageId(it), Modifier.fillMaxSize(), vehicles.imageRequests,
                    backgroundOnly = true, onColor = { vehicleColor = it })
            }
        }) { vehicle ->
        if (foreground)
        VehicleImage(imageId(vehicle.savedVehicle.model), Modifier.fillMaxSize(), vehicles.imageRequests,
            backgroundOnly = true, onColor = { vehicleColor = it })
    }
}
