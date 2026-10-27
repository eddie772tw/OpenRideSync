package org.openridesync.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

internal enum class OemUiAsset(val resourceName: String) {
    BLUETOOTH("oem_ui_bluetooth"), MUSIC("oem_ui_music")
}

/** Standard 24-unit Material icon paths provide a resource-independent public-build fallback. */
internal enum class OemRideAsset(val resourceName: String, val fallbackPath: String) {
    NAVIGATION("menu_navigation", "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z"),
    CALL("menu_call", "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1C10.61,21 3,13.39 3,4c0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02z"),
    MESSAGE("menu_message", "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,14H6v-2h12zM18,11H6V9h12zM18,8H6V6h12z"),
    MUSIC("menu_music", "M12,3v9.28c-0.47,-0.17 -0.97,-0.28 -1.5,-0.28C8.01,12 6,14.01 6,16.5S8.01,21 10.5,21 15,18.99 15,16.5V6h4V3z"),
    VOLUME("menu_volume", "M3,9v6h4l5,5V4L7,9zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"),
    BACK("back_arrow", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20z"),
    SHUTDOWN("shutdown", "M17,7l-1.41,1.41L18.17,11H8v2h10.17l-2.58,2.58L17,17l5,-5zM4,5h8V3H4c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h8v-2H4z"),
    // Phone administration keeps native icons when the optional original asset is absent.
    ACCOUNT("account", "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"),
    TRIPS("trips", "M19,15c-1.3,0 -2.4,0.84 -2.82,2H7c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2h10c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4H7.82C7.4,3.84 6.3,3 5,3 3.34,3 2,4.34 2,6s1.34,3 3,3c1.3,0 2.4,-0.84 2.82,-2H17c1.1,0 2,0.9 2,2s-0.9,2 -2,2H7c-2.21,0 -4,1.79 -4,4s1.79,4 4,4h9.18c0.42,1.16 1.52,2 2.82,2 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3z"),
    CONFIRM("action_check", "M9,16.17L4.83,12 3.41,13.41 9,19 21,7 19.59,5.59z"),
    CANCEL("action_cancel", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"),
    REPLY("action_reply", "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z"),
    ANSWER("action_answer", CALL.fallbackPath),
    HANG_UP("action_hang_up", "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 -0.18,0.18 -0.43,0.28 -0.68,0.28 -0.26,0 -0.51,-0.1 -0.71,-0.29L0.31,13.1c-0.19,-0.18 -0.3,-0.43 -0.3,-0.7 0,-0.28 0.11,-0.53 0.3,-0.71C3.36,8.79 7.47,7 12,7s8.64,1.79 11.69,4.69c0.19,0.18 0.3,0.43 0.3,0.71 0,0.27 -0.11,0.52 -0.3,0.7l-2.48,2.46c-0.2,0.19 -0.45,0.29 -0.71,0.29 -0.25,0 -0.5,-0.1 -0.68,-0.28 -0.79,-0.73 -1.68,-1.36 -2.66,-1.85 -0.33,-0.16 -0.56,-0.51 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z"),
    VOLUME_UP("action_volume_up", VOLUME.fallbackPath),
    VOLUME_DOWN("action_volume_down", "M3,9v6h4l5,5V4L7,9zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z"),
}

/** Original ride colors and proportions, without the small phone icon's white backing. */
@SuppressLint("DiscouragedApi")
@Composable
internal fun RideIcon(asset: OemRideAsset, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val packageName = LocalContext.current.packageName
    val id = remember(resources, configuration, packageName, asset) {
        resources.getIdentifier("oem_ride_phone_${asset.resourceName}", "drawable", packageName)
    }
    if (id != 0) Image(painterResource(id), contentDescription = null, modifier = modifier.size(size))
    else {
        val fallback = remember(asset) {
            ImageVector.Builder(asset.name, 24.dp, 24.dp, 24f, 24f)
                .addPath(PathParser().parsePathString(asset.fallbackPath).toNodes(), fill = SolidColor(Color.Black))
                .build()
        }
        Icon(fallback, contentDescription = null, modifier = modifier.size(size))
    }
}

/** Optional private resources. Keep these names out of connection and player business logic. */
@SuppressLint("DiscouragedApi") // Public builds deliberately have no OEM resource IDs.
@Composable
internal fun OemUiIcon(asset: OemUiAsset, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val packageName = LocalContext.current.packageName
    // Resources can retain its identity across a configuration change.
    val id = remember(resources, configuration, packageName, asset) {
        resources.getIdentifier(asset.resourceName, "drawable", packageName)
    }
    if (id != 0) {
        // The dark OEM Bluetooth path needs a light backing even with a future dark theme.
        Box(modifier.background(Color.White, CircleShape).padding(8.dp)) {
            Image(painterResource(id), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@SuppressLint("DiscouragedApi")
@Composable
internal fun oemUiFontFamily(): FontFamily {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val packageName = LocalContext.current.packageName
    return remember(resources, configuration, packageName) {
        val regular = resources.getIdentifier("oem_ui_regular", "font", packageName)
        val medium = resources.getIdentifier("oem_ui_medium", "font", packageName)
        if (regular == 0 || medium == 0) FontFamily.SansSerif
        else FontFamily(Font(regular, FontWeight.Normal), Font(medium, FontWeight.Medium))
    }
}
