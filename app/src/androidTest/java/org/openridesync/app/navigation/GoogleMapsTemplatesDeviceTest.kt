package org.openridesync.app.navigation

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Read installed raw resources only. Does not launch Maps, acquire a route or inspect notifications.
 * Missing Maps skips; installed-but-incompatible/partial templates fails with aggregate counts.
 * Requires the app's Maps package-visibility declaration; hidden packages also skip as unavailable. */
@RunWith(AndroidJUnit4::class)
class GoogleMapsTemplatesDeviceTest {
    @Test fun installedMapsProvidesCompleteOemNamedTemplates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val available = try {
            context.packageManager.getPackageInfo("com.google.android.apps.maps", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        assumeTrue("Maps package unavailable to this app", available)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val matcher = worker.submit<GoogleMapsManeuvers> { GoogleMapsManeuvers(context) }.get(30, TimeUnit.SECONDS)
            // Includes the non-projectable CONGESTION template as an exclusion candidate.
            val missing = NavigationProjection.Maneuver.entries.toSet() -
                NavigationProjection.Maneuver.MYLOCATION - matcher.recognizableManeuvers
            val details = "templates=${matcher.templateCount}/62, " +
                "recognizable=${matcher.recognizableManeuvers.size}/61, " +
                "missing=${missing.map { it.name }.sorted().joinToString(",")}"
            assertEquals("Missing or incompatible Maps raw templates: $details", 62, matcher.templateCount)
            assertEquals("Missing projectable maneuvers: $details", 61, matcher.recognizableManeuvers.size)
        } finally { worker.shutdownNow() }
    }
}
