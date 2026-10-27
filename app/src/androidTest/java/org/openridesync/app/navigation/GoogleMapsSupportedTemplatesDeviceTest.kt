package org.openridesync.app.navigation

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.openridesync.app.navigation.NavigationProjection.Maneuver

/** Resource-derived synthetic inputs only, no Maps launch, notification or route observation.
 * Separate from the intentionally strict complete-coverage audit in GoogleMapsTemplatesDeviceTest. */
@RunWith(AndroidJUnit4::class)
class GoogleMapsSupportedTemplatesDeviceTest {
    @Test fun installedRightTurnAndNormalizedMirrorAreRecognizedWithoutGuessingUnknowns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = try { context.packageManager.getPackageInfo("com.google.android.apps.maps", 0) }
            catch (_: PackageManager.NameNotFoundException) { null }
        assumeTrue("Maps package unavailable to this app", info != null)
        val version = requireNotNull(info).longVersionCode
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                val matcher = GoogleMapsManeuvers(context)
                val resources = context.packageManager.getResourcesForApplication("com.google.android.apps.maps")
                val name = if (version in 1066546602L until 1066890621L) "da_turn_right" else "ic_turn_right"
                val id = resources.getIdentifier(name, "raw", "com.google.android.apps.maps")
                assertTrue("Installed Maps lacks known right-turn resource", id != 0)
                val bytes = resources.openRawResource(id).use { it.readBytes() }
                assertTrue("Unexpectedly large known template", bytes.size <= 256 * 1024)
                val right = requireNotNull(GoogleMapsManeuvers.render(bytes,
                    context.resources.displayMetrics.densityDpi * 36 / 120))
                try {
                    assertEquals(Maneuver.RIGHT, matcher.detect(right))
                    val pixels = requireNotNull(GoogleMapsManeuvers.normalize(right))
                    val mirror = GoogleMapsManeuvers.mirror(pixels)
                    val left = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                        setPixels(mirror.map {
                            when (it.toInt()) { 0 -> Color.TRANSPARENT; 125 -> Color.argb(125, 255, 255, 255); else -> Color.WHITE }
                        }.toIntArray(), 0, 64, 0, 0, 64, 64)
                    }
                    val blank = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    try {
                        assertEquals(Maneuver.LEFT, matcher.detect(left))
                        assertNull(matcher.detect(blank))
                        val tie = GoogleMapsManeuvers(listOf(
                            GoogleMapsManeuvers.Template(Maneuver.RIGHT, pixels),
                            GoogleMapsManeuvers.Template(Maneuver.LEFT, pixels)))
                        assertFalse(tie.isComplete)
                        assertNull(tie.detect(right))
                    } finally { left.recycle(); blank.recycle() }
                } finally { right.recycle() }
            }.get(30, TimeUnit.SECONDS)
        } finally { worker.shutdownNow() }
    }
}
