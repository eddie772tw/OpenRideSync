package org.openridesync.app.runtime

import android.app.NotificationManager
import org.openridesync.app.ble.Gen1Projection

/** Native boundary: fixed-volume and bound actions are hidden rather than OEM's always-on arrows.
 * State is platform readback; missing state is never rendered as a measured zero volume. */
internal object VolumeProjection {
    fun menu(label: String, first: Boolean, last: Boolean) = Gen1Projection.Menu(17, 16, header = label,
        up = if (first) null else Gen1Projection.Arrow.UP,
        down = if (last) null else Gen1Projection.Arrow.DOWN,
        right = Gen1Projection.Arrow.RIGHT_BIG, contentIcon = 5)

    fun live(state: RideVolume.State?, label: String): Gen1Projection.Live {
        val muted = state?.stream == RideVolume.Stream.MEDIA &&
            state.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
        val level = if (muted) 0 else state?.level
        val down = state != null && !state.fixed && state.current > state.minimum
        // Increase while muted requests unmute even when the raw volume is at its upper bound.
        val up = state != null && (state.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            (!state.fixed && state.current < state.maximum))
        return Gen1Projection.Live(17, 18, header = label,
            left = if (down) Gen1Projection.Action(Gen1Projection.Arrow.DOWN, 10, 16) else Gen1Projection.Action(),
            right = if (up) Gen1Projection.Action(Gen1Projection.Arrow.UP, 11, 16) else Gen1Projection.Action(),
            contentIcon = if (level != null && level in 0..5) 118 + level else 117)
    }
}
