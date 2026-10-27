package org.openridesync.app.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** Keep system-bar contrast aligned with the phone override, including navigation scrims. */
internal fun ComponentActivity.applyAppearanceSystemBars(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(0xe6ffffff.toInt(), 0x801b1b1b.toInt()) { dark },
    )
}
