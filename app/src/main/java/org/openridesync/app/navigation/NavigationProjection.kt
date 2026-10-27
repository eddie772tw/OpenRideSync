package org.openridesync.app.navigation

import org.openridesync.adapter.MusicDisplayText
import org.openridesync.app.ble.Gen1Projection

/** Read-only Gen1 active-navigation icon page. A trusted route owner supplies the resolved maneuver and distance. */
internal object NavigationProjection {
    // Wire bytes from Assignments.IconId, not enum ordinals.
    enum class Maneuver(val icon: Int) {
        LANEGUIDANCE_TICK(22),
        LANE_DOTS(18), LANE_NORMAL(24), LANE_NORMAL_SHORT(23),
        LANE_SHARP(26), LANE_SHARP_SHORT(25),
        LANE_SLIGHT(28), LANE_SLIGHT_TALL(27),
        LANE_STRAIGHT(20), LANE_STRAIGHT_TALL(19), LANE_STUB(21),
        LANE_UTURN(30), LANE_UTURN_SHORT(29),

        ARRIVE(40), ARRIVE_RIGHT(52), DEPART(41), FERRY(78),
        FORK_RIGHT(48), GENERIC_MERGE(42), GENERIC_ROUNDABOUT(49),
        MYLOCATION(43), RAMP_RIGHT(50), RIGHT(44),
        ROUNDABOUT_1(62), ROUNDABOUT_2(63), ROUNDABOUT_3(64), ROUNDABOUT_4(65),
        ROUNDABOUT_5(66), ROUNDABOUT_6(67), ROUNDABOUT_7(68), ROUNDABOUT_8(69),
        ROUNDABOUT_EXIT(51), SHARP_RIGHT(45), SLIGHT_RIGHT(46),
        STRAIGHT(39), U_TURN(47), ARRIVE_LEFT(61),
        FORK_LEFT(57), RAMP_LEFT(59), LEFT(53),
        SHARP_LEFT(54), SLIGHT_LEFT(55),

        LANE_NORMAL_LEFT(32), LANE_NORMAL_SHORT_LEFT(31),
        LANE_SHARP_LEFT(34), LANE_SHARP_SHORT_LEFT(33),
        LANE_SLIGHT_LEFT(36), LANE_SLIGHT_TALL_LEFT(35),
        LANE_UTURN_LEFT(38), LANE_UTURN_SHORT_LEFT(37),

        ROUNDABOUT_1_LEFT(70), ROUNDABOUT_2_LEFT(71),
        ROUNDABOUT_3_LEFT(72), ROUNDABOUT_4_LEFT(73),
        ROUNDABOUT_5_LEFT(74), ROUNDABOUT_6_LEFT(75),
        ROUNDABOUT_7_LEFT(76), ROUNDABOUT_8_LEFT(77),
        ROUNDABOUT_EXIT_LEFT(60), GENERIC_ROUNDABOUT_LEFT(58),
        U_TURN_LEFT(56), PLACE(40),
    }

    fun menu(header: String, first: Boolean, last: Boolean, active: MapsInstruction? = null): Gen1Projection.Menu =
        Gen1Projection.Menu(5, 4, header = fitHeader(header),
            up = if (first) null else Gen1Projection.Arrow.UP,
            down = if (last) null else Gen1Projection.Arrow.DOWN,
            right = Gen1Projection.Arrow.RIGHT_BIG,
            contentIcon = active?.maneuver?.icon ?: if (active != null) 92 else 2,
            line1 = active?.nextStep?.let(::safeDistance))

    fun waiting(header: String, canCancel: Boolean = false, canRepeat: Boolean = false): Gen1Projection.Live =
        Gen1Projection.Live(5, 6, header = fitHeader(header),
            left = if (canCancel) Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 1, 4)
                else Gen1Projection.Action(),
            right = if (canRepeat) Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 6, 4)
                else Gen1Projection.Action())

    fun cancel(header: String): Gen1Projection.Live = Gen1Projection.Live(5, 6,
        header = fitHeader(header), contentIcon = 101,
        left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 15, 4),
        right = Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 9, 4))

    fun active(maneuver: Maneuver?, distanceToNext: String?, header: String,
        canCancel: Boolean = false, canRepeat: Boolean = false): Gen1Projection.Live? {
        if (maneuver == null) return null
        val title = fitHeader(header)
        if (title.isBlank()) return null
        // Never clip a distance: losing its unit or digits could change the instruction.
        return Gen1Projection.Live(5, 6, header = title, contentIcon = maneuver.icon,
            line1 = distanceToNext?.let(::safeDistance),
            left = if (canCancel) Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 1, 4)
                else Gen1Projection.Action(),
            right = if (canRepeat) Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 6, 4)
                else Gen1Projection.Action())
    }

    private fun safeDistance(value: String): String? = value.takeIf {
        it.toByteArray(Charsets.UTF_8).size <= 30 && it.isNotBlank() && MusicDisplayText.prepare(it) == it
    }

    /** Bound the header at a Unicode scalar boundary; the distance is retained whole or omitted. */
    private fun fitHeader(raw: String): String {
        val text = MusicDisplayText.prepare(raw)
        var offset = 0
        var bytes = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > 120) break
            bytes += size
            offset += Character.charCount(point)
        }
        return text.substring(0, offset)
    }
}
