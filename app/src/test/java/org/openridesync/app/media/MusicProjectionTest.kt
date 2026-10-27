package org.openridesync.app.media

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.ble.Gen1Projection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

class MusicProjectionTest {
    @Test fun popupRetainsGreyPaletteAndBoundsSanitizedTextWithoutInventingBlankMetadata() {
        assertNull(MusicProjection.popup("\u202e", "\n\t"))
        val page = MusicProjection.popup("e\u0301", "Artist")!!
        assertEquals(Gen1Projection.Popup(16, "é - Artist", contentIcon = 94), page)
        assertEquals("Artist", MusicProjection.popup(null, "Artist")!!.text)
        val long = MusicProjection.popup("😀漢".repeat(200), "Artist")!!
        assertTrue(long.text.toByteArray(Charsets.UTF_8).size <= 90)
        assertTrue(long.text.toByteArray(Charsets.UTF_8).size >= 87)
        assertEquals(long.text, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(long.text.toByteArray(Charsets.UTF_8))).toString())
        assertTrue(Gen1Projection.encode(long, 255).size <= 512)
        assertTrue(Gen1Projection.supports(page, 2))
        assertFalse(Gen1Projection.supports(page, 4))
    }

    private fun state(title: String?, artist: String?, playback: MediaPlayback = MediaPlayback.PLAYING,
                      actions: Long = PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT,
                      label: String = "Player") = MediaState(MediaAccess.GRANTED,
        listOf(MediaSessionChoice("selected", label, "synthetic.invalid")), "selected", title, artist, playback, actions)

    @Test fun menuUsesPlaybackPresentationWithoutDiscardingPausedSelection() {
        fun menu(value: MediaState?, first: Boolean = false, last: Boolean = false) =
            MusicProjection.menu(value, "Music", "Playing", "Unknown", Locale.ROOT, false, first, last)
        val paused = state("Retained title", "Artist", MediaPlayback.PAUSED, label = "Not a menu header")
        val inactive = menu(paused, first = true)
        assertEquals("selected", paused.selectedId)
        assertEquals("Music", inactive.header); assertEquals(3, inactive.contentIcon)
        assertNull(inactive.line1); assertNull(inactive.line2); assertNull(inactive.left); assertNull(inactive.up)
        assertEquals(Gen1Projection.Arrow.DOWN, inactive.down)
        assertEquals(Gen1Projection.Arrow.RIGHT, inactive.right)
        assertEquals(14, inactive.color); assertEquals(13, inactive.colorLight)
        assertEquals(3, menu(null).contentIcon)
        val icon = menu(state(null, null), last = true)
        assertEquals("Playing", icon.header); assertEquals(94, icon.contentIcon)
        assertEquals(Gen1Projection.Arrow.RIGHT_BIG, icon.right); assertNull(icon.down)
        val text = menu(state("Song", "\u202e"))
        assertNull(text.contentIcon); assertEquals("Song", text.line1); assertEquals("Unknown", text.line2)
    }

    @Test fun menuFitsActualCombinedFrameAndUtf8Fields() {
        val value = "😀漢".repeat(512)
        val page = MusicProjection.menu(state(value, value, label = "Never header"), value, value,
            "Unknown", Locale.ROOT, false, first = false, last = false)
        val frame = Gen1Projection.encode(page, 255)
        assertTrue(frame.size in 90..111)
        for (text in listOf(page.header, page.line1, page.line2)) {
            val bytes = text!!.toByteArray(Charsets.UTF_8)
            assertTrue(bytes.size <= 30)
            assertEquals(text, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString())
        }
        assertEquals("İSTANBUL", MusicProjection.menu(null, "istanbul", "Playing", "Unknown",
            Locale.forLanguageTag("tr"), true, true, true).header)
    }

    @Test fun metadataCleanupFallbackLocaleAndCapabilityActions() {
        val page = MusicProjection.live(state("e\u0301\u202e", "\u0000"), "未知", Locale.ROOT, false)
        assertEquals("é", page.line1); assertEquals("未知", page.line2)
        assertEquals(14, page.color); assertEquals(15, page.colorDark)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 4, 13), page.left)
        assertEquals(Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 5, 13), page.right)
        assertEquals("İSTANBUL", MusicProjection.live(state("istanbul", "artist"), "unknown", Locale.forLanguageTag("tr"), true).line1)
        val blank = MusicProjection.live(state("\u202e", "\n\t"), "未知", Locale.ROOT, false)
        assertEquals(94, blank.contentIcon); assertNull(blank.line1); assertNull(blank.line2)
        val artistOnly = MusicProjection.live(state(null, "演出者"), "未知", Locale.ROOT, false)
        assertEquals("未知", artistOnly.line1); assertEquals("演出者", artistOnly.line2)
        val disabled = MusicProjection.live(state("title", "artist", actions = 0), "未知", Locale.ROOT, false)
        assertNull(disabled.left.arrow); assertNull(disabled.right.arrow)
        for (playback in listOf(MediaPlayback.UNKNOWN, MediaPlayback.ERROR, MediaPlayback.OTHER, MediaPlayback.BUFFERING, MediaPlayback.CONNECTING)) {
            assertNull(MusicProjection.live(state("t", "a", playback, PlaybackState.ACTION_PLAY), "unknown", Locale.ROOT, false).left.arrow)
        }
        for (playback in listOf(MediaPlayback.NONE, MediaPlayback.STOPPED, MediaPlayback.PAUSED)) {
            assertEquals(3, MusicProjection.live(state("t", "a", playback, PlaybackState.ACTION_PLAY), "unknown", Locale.ROOT, false).left.contentIcon)
        }
    }

    @Test fun aggregateBudgetAndScalarBoundariesSurviveLongCjkAndEmoji() {
        for (value in listOf("漢".repeat(512), "😀".repeat(512), "👩‍👩‍👧‍👦".repeat(100))) {
            val page = MusicProjection.live(state(value, value, label = value), "未知", Locale.ROOT, false)
            val frame = Gen1Projection.encode(page, 255)
            assertTrue(frame.size <= 114)
            assertTrue(frame.size >= 90)
            for (text in listOf(page.header, page.line1, page.line2)) {
                val bytes = text!!.toByteArray(Charsets.UTF_8)
                assertTrue(bytes.size <= 30)
                assertEquals(text, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
                assertTrue(text.codePoints().noneMatch { it in 0xd800..0xdfff })
            }
        }
        val single = MusicProjection.live(state("漢".repeat(512), "A", actions = 0, label = "P"), "未知", Locale.ROOT, false)
        assertEquals(30, single.line1!!.toByteArray(Charsets.UTF_8).size)
        assertEquals("A", single.line2)
    }
}
