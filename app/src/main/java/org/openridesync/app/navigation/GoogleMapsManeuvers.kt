package org.openridesync.app.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.PictureDrawable
import android.os.Looper
import android.util.Xml
import com.caverock.androidsvg.SVG
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/** Installed Maps resources only. Construct and call off Main (normally Dispatchers.Default).
 * OEM pixel comparison, with deliberate fail-closed ties, blank images and non-square inputs.
 * No route data, external resolver, logging or persistence. */
internal class GoogleMapsManeuvers internal constructor(private val templates: List<Template>) {
    constructor(context: Context) : this(load(context))

    val templateCount: Int get() = templates.size
    val isComplete: Boolean get() = templateCount == expectedTemplateCount
    val recognizableManeuvers: Set<NavigationProjection.Maneuver>
        get() = templates.mapNotNull { it.maneuver }.toSet()

    fun detect(bitmap: Bitmap): NavigationProjection.Maneuver? {
        checkWorker()
        val pixels = normalize(bitmap) ?: return null
        // Missing competitors must not turn a merely similar input into a known direction.
        return match(pixels, templates, minimumExclusive = if (isComplete) 80 else 99)
    }

    internal data class Template(val maneuver: NavigationProjection.Maneuver?, val pixels: ByteArray)

    internal companion object {
        val expectedTemplateCount: Int get() = names.sumOf { if (it.mirrored == null) 1 else 2 }
        private const val MAPS = "com.google.android.apps.maps"
        private const val MAX_SVG_BYTES = 256 * 1024
        private const val SIDE = 64
        private const val STANDARD_SVG_DOCTYPE = "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">"
        private fun checkWorker() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Maps templates require a worker thread" }
        }

        internal fun normalize(bitmap: Bitmap): ByteArray? {
            if (bitmap.isRecycled || bitmap.width != bitmap.height || bitmap.width !in 1..2048) return null
            val source = try {
                IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            } catch (_: RuntimeException) { return null }
            return ByteArray(SIDE * SIDE) { index ->
                val x = (index % SIDE * (bitmap.width / 64.0)).toInt()
                val y = (index / SIDE * (bitmap.width / 64.0)).toInt()
                val color = source[y * bitmap.width + x]
                when {
                    (color ushr 24) in 1..254 -> 125
                    color == 0 -> 0
                    else -> -1
                }.toByte()
            }.takeIf { pixels -> pixels.any { it != 0.toByte() } }
        }

        internal fun mirror(pixels: ByteArray): ByteArray {
            require(pixels.size == SIDE * SIDE)
            return ByteArray(pixels.size) { i -> pixels[i / SIDE * SIDE + SIDE - 1 - i % SIDE] }
        }

        internal fun similarity(a: ByteArray, b: ByteArray): Int =
            if (a.size != SIDE * SIDE || b.size != a.size) 0
            else a.indices.count { a[it] == b[it] } * 100 / a.size

        internal fun match(pixels: ByteArray, templates: List<Template>, minimumExclusive: Int = 80): NavigationProjection.Maneuver? {
            if (pixels.size != SIDE * SIDE || pixels.all { it == 0.toByte() }) return null
            require(minimumExclusive in 80..99)
            var best = minimumExclusive
            var winner: NavigationProjection.Maneuver? = null
            var tied = false
            for (template in templates) {
                val score = similarity(pixels, template.pixels)
                if (score > best) { best = score; winner = template.maneuver; tied = false }
                else if (score == best && score > minimumExclusive) tied = true
            }
            return if (tied) null else winner
        }

        private fun load(context: Context): List<Template> {
            checkWorker()
            val manager = context.packageManager
            val info = try { manager.getPackageInfo(MAPS, 0) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { return emptyList() }
            val resources = try { manager.getResourcesForApplication(MAPS) }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { return emptyList() }
            val v2 = info.longVersionCode in 1066546602L until 1066890621L
            val side = context.resources.displayMetrics.densityDpi * 36 / 120
            if (side !in 1..2048) return emptyList()
            val result = mutableListOf<Template>()
            for (entry in names) {
                val name = if (v2) entry.v2 else entry.v1
                val id = resources.getIdentifier(name, "raw", MAPS)
                if (id == 0) continue
                val pixels = try {
                    val bytes = resources.openRawResource(id).use { input ->
                        val buffer = ByteArray(MAX_SVG_BYTES + 1)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            if (read == 0) throw java.io.IOException("No resource progress")
                            count += read
                        }
                        buffer.copyOf(count)
                    }
                    if (bytes.size > MAX_SVG_BYTES) continue
                    render(bytes, side)?.let { bitmap -> try { normalize(bitmap) } finally { bitmap.recycle() } }
                } catch (_: Exception) { null } ?: continue
                result += Template(entry.maneuver, pixels)
                entry.mirrored?.let { result += Template(it, mirror(pixels)) }
            }
            return result.toList()
        }

        /** Except for the verified entity-free SVG1.1 declaration, preflight rejects DTDs/external links.
         * No SVGExternalFileResolver is registered; no external assets or network are resolved. */
        internal fun render(bytes: ByteArray, side: Int): Bitmap? {
            if (bytes.isEmpty() || bytes.size > MAX_SVG_BYTES || side !in 1..2048) return null
            // Maps' verified FERRY resource includes this exact, entity-free declaration.
            // ISO-8859-1 round-trips every byte; all remaining declarations still fail preflight.
            val sanitized = bytes.toString(Charsets.ISO_8859_1)
                .replace(STANDARD_SVG_DOCTYPE, "").toByteArray(Charsets.ISO_8859_1)
            try {
                val xml = Xml.newPullParser()
                xml.setInput(ByteArrayInputStream(sanitized), null)
                var root = false
                while (true) {
                    when (xml.nextToken()) {
                        XmlPullParser.END_DOCUMENT -> break
                        XmlPullParser.DOCDECL, XmlPullParser.PROCESSING_INSTRUCTION -> return null
                        XmlPullParser.START_TAG -> {
                            if (!root) { if (xml.name != "svg") return null; root = true }
                            if (xml.name == "image" || xml.name == "script" || xml.name == "foreignObject") return null
                            for (i in 0 until xml.attributeCount) {
                                val value = xml.getAttributeValue(i).trim()
                                if (xml.getAttributeName(i) == "href" && !value.startsWith("#")) return null
                                if (externalCss(value)) return null
                            }
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (externalCss(xml.text)) return null
                    }
                }
                if (!root) return null
                val svg = SVG.getFromInputStream(ByteArrayInputStream(sanitized))
                svg.setDocumentWidth(side.toFloat())
                svg.setDocumentHeight(side.toFloat())
                val drawable = PictureDrawable(svg.renderToPicture())
                val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0, 0, side, side)
                    drawable.draw(Canvas(bitmap))
                    return bitmap
                } catch (failure: RuntimeException) { bitmap.recycle(); throw failure }
            } catch (_: Exception) { return null }
        }

        private fun externalCss(value: String): Boolean =
            value.contains("@import", ignoreCase = true) ||
                Regex("""url\(([^)]*)\)""", RegexOption.IGNORE_CASE).findAll(value).any {
                    !it.groupValues[1].trim().trim('\'', '"').startsWith("#")
                }

        private data class Name(val maneuver: NavigationProjection.Maneuver?, val v1: String,
            val v2: String, val mirrored: NavigationProjection.Maneuver? = null)
        // CONGESTION has no projection icon: keep its null candidate to prevent a false turn.
        private val names = listOf(
            Name(NavigationProjection.Maneuver.ARRIVE_RIGHT, "ic_arrive_right", "da_turn_arrive_right", NavigationProjection.Maneuver.ARRIVE_LEFT),
            Name(NavigationProjection.Maneuver.FORK_RIGHT, "ic_merge_slight_right", "da_turn_fork_right", NavigationProjection.Maneuver.FORK_LEFT),
            Name(NavigationProjection.Maneuver.RAMP_RIGHT, "ic_merge_right", "da_turn_ramp_right", NavigationProjection.Maneuver.RAMP_LEFT),
            Name(NavigationProjection.Maneuver.RIGHT, "ic_turn_right", "da_turn_right", NavigationProjection.Maneuver.LEFT),
            Name(NavigationProjection.Maneuver.SHARP_RIGHT, "ic_turn_sharp_right", "da_turn_sharp_right", NavigationProjection.Maneuver.SHARP_LEFT),
            Name(NavigationProjection.Maneuver.SLIGHT_RIGHT, "ic_turn_slight_right", "da_turn_slight_right", NavigationProjection.Maneuver.SLIGHT_LEFT),
            Name(NavigationProjection.Maneuver.LANE_NORMAL, "lane_normal", "da_lane_normal", NavigationProjection.Maneuver.LANE_NORMAL_LEFT),
            Name(NavigationProjection.Maneuver.LANE_NORMAL_SHORT, "lane_normal_short", "da_lane_normal_short", NavigationProjection.Maneuver.LANE_NORMAL_SHORT_LEFT),
            Name(NavigationProjection.Maneuver.LANE_SHARP, "lane_sharp", "da_lane_sharp", NavigationProjection.Maneuver.LANE_SHARP_LEFT),
            Name(NavigationProjection.Maneuver.LANE_SHARP_SHORT, "lane_sharp_short", "da_lane_sharp_short", NavigationProjection.Maneuver.LANE_SHARP_SHORT_LEFT),
            Name(NavigationProjection.Maneuver.LANE_SLIGHT, "lane_slight", "da_lane_slight", NavigationProjection.Maneuver.LANE_SLIGHT_LEFT),
            Name(NavigationProjection.Maneuver.LANE_SLIGHT_TALL, "lane_slight_tall", "da_lane_slight_tall", NavigationProjection.Maneuver.LANE_SLIGHT_TALL_LEFT),
            Name(NavigationProjection.Maneuver.LANE_UTURN, "lane_uturn", "da_lane_uturn", NavigationProjection.Maneuver.LANE_UTURN_LEFT),
            Name(NavigationProjection.Maneuver.LANE_UTURN_SHORT, "lane_uturn_short", "da_lane_uturn_short", NavigationProjection.Maneuver.LANE_UTURN_SHORT_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_1, "ic_roundabout_sharp_right", "da_turn_roundabout_1", NavigationProjection.Maneuver.ROUNDABOUT_1_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_2, "ic_roundabout_right", "da_turn_roundabout_2", NavigationProjection.Maneuver.ROUNDABOUT_2_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_3, "ic_roundabout_slight_right", "da_turn_roundabout_3", NavigationProjection.Maneuver.ROUNDABOUT_3_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_4, "ic_roundabout_straight", "da_turn_roundabout_4", NavigationProjection.Maneuver.ROUNDABOUT_4_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_5, "ic_roundabout_slight_left", "da_turn_roundabout_5", NavigationProjection.Maneuver.ROUNDABOUT_5_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_6, "ic_roundabout_left", "da_turn_roundabout_6", NavigationProjection.Maneuver.ROUNDABOUT_6_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_7, "ic_roundabout_sharp_left", "da_turn_roundabout_7", NavigationProjection.Maneuver.ROUNDABOUT_7_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_8, "ic_roundabout_u_turn", "da_turn_roundabout_8", NavigationProjection.Maneuver.ROUNDABOUT_8_LEFT),
            Name(NavigationProjection.Maneuver.ROUNDABOUT_EXIT, "ic_roundabout_exit", "da_turn_roundabout_exit", NavigationProjection.Maneuver.ROUNDABOUT_EXIT_LEFT),
            Name(NavigationProjection.Maneuver.GENERIC_ROUNDABOUT, "da_turn_generic_roundabout", "da_turn_generic_roundabout", NavigationProjection.Maneuver.GENERIC_ROUNDABOUT_LEFT),
            Name(NavigationProjection.Maneuver.U_TURN, "ic_u_turn", "da_turn_uturn", NavigationProjection.Maneuver.U_TURN_LEFT),
            Name(NavigationProjection.Maneuver.LANE_DOTS, "lane_dots", "da_lane_dots"),
            Name(NavigationProjection.Maneuver.LANE_STRAIGHT, "lane_straight", "da_lane_straight"),
            Name(NavigationProjection.Maneuver.LANE_STRAIGHT_TALL, "lane_straight_tall", "da_lane_straight_tall"),
            Name(NavigationProjection.Maneuver.LANE_STUB, "lane_stub", "da_lane_stub"),
            Name(NavigationProjection.Maneuver.DEPART, "ic_depart", "da_turn_depart"),
            Name(NavigationProjection.Maneuver.GENERIC_MERGE, "ic_merge", "da_turn_generic_merge"),
            Name(NavigationProjection.Maneuver.STRAIGHT, "ic_straight", "da_turn_straight"),
            Name(NavigationProjection.Maneuver.LANEGUIDANCE_TICK, "da_laneguidance_tick", "da_laneguidance_tick"),
            Name(NavigationProjection.Maneuver.ARRIVE, "da_turn_arrive", "da_turn_arrive"),
            Name(NavigationProjection.Maneuver.FERRY, "da_turn_ferry", "da_turn_ferry"),
            Name(NavigationProjection.Maneuver.PLACE, "ic_place", "ic_place"),
            Name(null, "traffic_icon_gray_circle", "traffic_icon_gray_circle"),
        )
    }
}
