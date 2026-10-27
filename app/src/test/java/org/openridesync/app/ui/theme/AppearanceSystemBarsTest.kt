package org.openridesync.app.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AppearanceSystemBarsTest {
    @Test fun forcedModesUpdateBothBarIconAppearancesInBothDirections() {
        val owner = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = owner.get()
            val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            for (dark in listOf(true, false, true)) {
                activity.applyAppearanceSystemBars(dark)
                assertEquals(!dark, bars.isAppearanceLightStatusBars)
                assertEquals(!dark, bars.isAppearanceLightNavigationBars)
            }
        } finally { owner.pause().stop().destroy() }
    }
}
