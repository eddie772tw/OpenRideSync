package org.openridesync.app.navigation

import android.graphics.Bitmap
import android.graphics.Color
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.openridesync.app.navigation.GoogleMapsManeuvers.Companion.normalize
import org.openridesync.app.navigation.GoogleMapsManeuvers.Companion.mirror
import org.openridesync.app.navigation.GoogleMapsManeuvers.Companion.similarity
import org.openridesync.app.navigation.GoogleMapsManeuvers.Companion.match
import org.openridesync.app.navigation.GoogleMapsManeuvers.Companion.render
import org.openridesync.app.navigation.GoogleMapsManeuvers.Template
import org.openridesync.app.navigation.NavigationProjection.Maneuver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoogleMapsManeuversTest {
    @Test fun normalizationKeepsOemAlphaSamplingAndMirror() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.WHITE)
        bitmap.setPixel(2, 0, Color.argb(125, 255, 255, 255))
        bitmap.setPixel(126, 126, Color.BLACK)
        val pixels = requireNotNull(normalize(bitmap))
        assertEquals((-1).toByte(), pixels[0])
        assertEquals(125.toByte(), pixels[1])
        assertEquals(0.toByte(), pixels[2])
        assertEquals((-1).toByte(), pixels.last())
        val mirrored = mirror(pixels)
        assertEquals(pixels[0], mirrored[63])
        assertEquals(pixels.last(), mirrored[63 * 64])
        assertArrayEquals(pixels, mirror(mirrored))
        assertNull(normalize(Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)))
        assertNull(normalize(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)))
        bitmap.recycle()
        assertNull(normalize(bitmap))
    }

    @Test fun strictThresholdTiesAndNonProjectableCongestionFailClosed() {
        val input = ByteArray(4096) { (-1).toByte() }
        val eighty = input.copyOf().apply { repeat(819) { this[it] = 0 } }
        val eightyOne = input.copyOf().apply { repeat(778) { this[it] = 0 } }
        assertEquals(80, similarity(input, eighty))
        assertNull(match(input, listOf(Template(Maneuver.RIGHT, eighty))))
        assertEquals(Maneuver.RIGHT, match(input, listOf(Template(Maneuver.RIGHT, eightyOne))))
        assertNull(match(input, listOf(Template(Maneuver.RIGHT, input), Template(Maneuver.LEFT, input))))
        assertNull(match(input, listOf(Template(Maneuver.RIGHT, eightyOne), Template(null, input))))
        assertNull(match(ByteArray(4096), listOf(Template(Maneuver.RIGHT, ByteArray(4096)))))
        assertNull(match(input, emptyList()))
        assertEquals(0, similarity(input, byteArrayOf(1)))
    }

    @Test fun partialTemplatesAcceptOnlyUniqueExactPixels() {
        val pixels = ByteArray(4096) { (-1).toByte() }
        val templates = listOf(Template(Maneuver.RIGHT, pixels))
        val matcher = GoogleMapsManeuvers(templates)
        assertEquals(62, GoogleMapsManeuvers.expectedTemplateCount)
        assertEquals(1, matcher.templateCount)
        assertFalse(matcher.isComplete)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            for (changed in listOf(0, 1, 778, 4096)) {
                val candidate = pixels.copyOf().apply { repeat(changed) { this[it] = 0 } }
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                    setPixels(candidate.map { if (it == 0.toByte()) 0 else Color.WHITE }.toIntArray(),
                        0, 64, 0, 0, 64, 64)
                }
                try {
                    assertEquals(if (changed == 0) Maneuver.RIGHT else null,
                        worker.submit<NavigationProjection.Maneuver?> { matcher.detect(bitmap) }
                            .get(5, java.util.concurrent.TimeUnit.SECONDS))
                } finally { bitmap.recycle() }
            }
        } finally { worker.shutdownNow() }
        val ninetyNine = pixels.copyOf().apply { this[0] = 0 }
        val eightyOne = pixels.copyOf().apply { repeat(778) { this[it] = 0 } }
        assertEquals(99, similarity(pixels, ninetyNine))
        assertEquals(81, similarity(pixels, eightyOne))
        assertNull(match(ninetyNine, templates, 99))
        assertNull(match(eightyOne, templates, 99))
        assertNull(match(pixels, templates + Template(Maneuver.LEFT, pixels), 99))
        assertNull(match(pixels, listOf(Template(null, pixels)), 99))
    }

    @Test fun fullSvgRendererSupportsTransformsAndRejectsExternalOrOversizedInput() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 10 10"><g transform="translate(5 0)"><path fill="#fff" d="M0 0H5V10H0Z"/></g></svg>"""
        val standardDtd = """<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">"""
        val bitmap = requireNotNull(render(svg.toByteArray(), 64))
        val withDtd = requireNotNull(render((standardDtd + svg).toByteArray(), 64))
        assertArrayEquals(normalize(bitmap), normalize(withDtd))
        withDtd.recycle()
        assertEquals(0, Color.alpha(bitmap.getPixel(8, 32)))
        assertEquals(255, Color.alpha(bitmap.getPixel(56, 32)))
        bitmap.recycle()
        for (unsafe in listOf(
            standardDtd.removeSuffix(">") + " [<!ENTITY x 'expanded'>]>" + svg,
            standardDtd + "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///not-read'>]>" + svg,
            """<!DOCTYPE svg [<!ENTITY x SYSTEM "file:///not-read">]><svg xmlns="http://www.w3.org/2000/svg"/>""",
            """<svg xmlns="http://www.w3.org/2000/svg"><use href="https://example.invalid/a.svg#x"/></svg>""",
            """<svg xmlns="http://www.w3.org/2000/svg"><style>@import 'https://example.invalid/a.css';</style></svg>""",
            """<svg xmlns="http://www.w3.org/2000/svg"><path fill="url(https://example.invalid/a)"/></svg>""",
        )) assertNull(render(unsafe.toByteArray(), 64))
        assertNull(render(ByteArray(256 * 1024 + 1), 64))
    }
}
