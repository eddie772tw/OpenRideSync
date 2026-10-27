package org.openridesync.app.media

import org.openridesync.adapter.MusicDisplayText
import org.openridesync.app.ble.Gen1Projection
import java.util.Locale

/** Semantic music display only. Caller owns capability, mode, action dispatch and transport. */
internal object MusicProjection {
    fun menu(state: MediaState?, title: String, playing: String, unknown: String, locale: Locale,
             highCase: Boolean, first: Boolean, last: Boolean): Gen1Projection.Menu {
        fun prepare(value: String?): String {
            val cleaned = MusicDisplayText.prepare(value)
            return if (highCase) MusicDisplayText.prepare(cleaned.uppercase(locale)) else cleaned
        }
        val selected = state?.takeIf { it.access == MediaAccess.GRANTED && it.selectedId != null &&
            it.sessions.count { choice -> choice.id == it.selectedId } == 1 }
        val active = selected?.playback == MediaPlayback.PLAYING
        val track = if (active) prepare(selected.title) else ""
        val artist = if (active) prepare(selected.artist) else ""
        val text = track.isNotEmpty() || artist.isNotEmpty()
        val fallback = prepare(unknown)
        require(!text || (track.isNotEmpty() && artist.isNotEmpty()) || fallback.isNotEmpty())
        val base = Gen1Projection.Menu(14, 13,
            up = if (first) null else Gen1Projection.Arrow.UP,
            down = if (last) null else Gen1Projection.Arrow.DOWN,
            right = if (active) Gen1Projection.Arrow.RIGHT_BIG else Gen1Projection.Arrow.RIGHT,
            contentIcon = if (!active) 3 else if (!text) 94 else null)
        val fields = listOf(prepare(if (active) playing else title),
            if (text) track.ifEmpty { fallback } else "", if (text) artist.ifEmpty { fallback } else "")
        val fitted = fields.map { Gen1Projection.prepareText(it) }
        return base.copy(header = fitted[0].ifEmpty { null }, line1 = fitted[1].ifEmpty { null },
            line2 = fitted[2].ifEmpty { null })
    }

    /** The lab music popup uses grey16 and ACTIVE_MUSIC94, without action buttons.
     * Empty metadata clears it; no synthetic title is substituted. Owner handles identity/time. */
    fun popup(title: String?, artist: String?): Gen1Projection.Popup? {
        val fields = listOf(MusicDisplayText.prepare(title), MusicDisplayText.prepare(artist))
        val text = fields.filter { it.isNotEmpty() }.joinToString(" - ")
        if (text.isEmpty()) return null
        return Gen1Projection.Popup(16, Gen1Projection.prepareText(text, 90), contentIcon = 94)
    }

    fun live(state: MediaState, unknown: String, locale: Locale, highCase: Boolean): Gen1Projection.Live {
        fun prepare(value: String?): String {
            val cleaned = MusicDisplayText.prepare(value)
            return if (highCase) MusicDisplayText.prepare(cleaned.uppercase(locale)) else cleaned
        }
        val selected = state.sessions.singleOrNull { it.id == state.selectedId }
            ?.takeIf { state.access == MediaAccess.GRANTED }
        val header = prepare(selected?.label)
        val title = prepare(if (selected != null) state.title else null)
        val artist = prepare(if (selected != null) state.artist else null)
        val empty = title.isEmpty() && artist.isEmpty()
        val fallback = prepare(unknown)
        require(empty || (title.isNotEmpty() && artist.isNotEmpty()) || fallback.isNotEmpty()) { "Localized unknown text must be visible" }
        val leftIcon = when (state.playback) {
            MediaPlayback.PLAYING -> if (state.canPause) 4 else null
            MediaPlayback.PAUSED, MediaPlayback.STOPPED, MediaPlayback.NONE -> if (state.canPlay) 3 else null
            else -> null // Unknown/transitional state is not evidence that Play is appropriate.
        }
        val base = Gen1Projection.Live(14, 15,
            left = if (selected != null && leftIcon != null)
                Gen1Projection.Action(Gen1Projection.Arrow.LEFT, leftIcon, 13) else Gen1Projection.Action(),
            right = if (selected != null && state.canNext)
                Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 5, 13) else Gen1Projection.Action(),
            contentIcon = if (empty) 94 else null)
        val fields = listOf(header, if (empty) "" else title.ifEmpty { fallback }, if (empty) "" else artist.ifEmpty { fallback })
        val fitted = fields.map { Gen1Projection.prepareText(it) }
        return base.copy(header = fitted[0].ifEmpty { null }, line1 = fitted[1].ifEmpty { null }, line2 = fitted[2].ifEmpty { null })
    }

}
