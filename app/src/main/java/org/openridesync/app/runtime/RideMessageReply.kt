package org.openridesync.app.runtime

import org.openridesync.app.ble.Gen1Projection

internal enum class RideReplyAvailability { READY, MICROPHONE_REQUIRED, RECONNECT_REQUIRED, UNAVAILABLE, NO_NETWORK }

/** Reply navigation and resolved saved OEM projection values. Drafts and selected bodies stay in RAM. */
internal object RideMessageReply {
    val categoryChoices = listOf("語音回覆", "快速回覆")

    fun text(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= 2000 &&
        it.none { character -> Character.isISOControl(character) && character != '\n' && character != '\t' } }

    /** Quick-list content is the saved OEM one-based index; template bodies never enter a meter frame. */
    fun page(stage: RidePageStage, choice: Int, error: RideReplyAvailability?,
        quickChoices: List<String> = emptyList()): Gen1Projection.Page {
        if (stage == RidePageStage.MESSAGE_CATEGORY) return Gen1Projection.Menu(11, 10,
            header = if (choice == 0) "Voice Message" else "Quick Message",
            up = if (choice == 0) null else Gen1Projection.Arrow.UP,
            down = if (choice == categoryChoices.lastIndex) null else Gen1Projection.Arrow.DOWN,
            left = Gen1Projection.Arrow.LEFT,
            right = if (choice == 0) Gen1Projection.Arrow.RIGHT_BIG else Gen1Projection.Arrow.RIGHT,
            contentIcon = if (choice == 0) 7 else 4)
        if (stage == RidePageStage.MESSAGE_QUICK_LIST) return Gen1Projection.Menu(11, 10,
            header = "Quick Message",
            up = if (choice > 0) Gen1Projection.Arrow.UP else null,
            down = if (choice < quickChoices.lastIndex) Gen1Projection.Arrow.DOWN else null,
            left = Gen1Projection.Arrow.LEFT,
            right = if (quickChoices.getOrNull(choice)?.let(::text) != null) Gen1Projection.Arrow.RIGHT_BIG else null,
            contentIcon = 4, line1 = if (quickChoices.isEmpty()) null else (choice + 1).toString())
        val cancel = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 1, 10)
        return when (stage) {
            RidePageStage.MESSAGE_LISTENING -> Gen1Projection.Live(11, 12, "Say Your Message", left = cancel, contentIcon = 7)
            RidePageStage.MESSAGE_CONFIRMATION -> Gen1Projection.Live(11, 12, "Review",
                left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 6, 10),
                right = Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 7, 3), contentIcon = 8)
            RidePageStage.MESSAGE_SENDING -> Gen1Projection.Live(11, 12, "Sending", contentIcon = 89)
            RidePageStage.MESSAGE_SENT -> Gen1Projection.Live(11, 12, "Sent", contentIcon = 9)
            RidePageStage.MESSAGE_NOT_SENT -> Gen1Projection.Live(11, 12, "Send Error", left = cancel, contentIcon = 86)
            // Native explicit retry adds the resolved retry action; it never auto-records.
            else -> Gen1Projection.Live(11, 12,
                if (error == RideReplyAvailability.NO_NETWORK) "No Network" else "Try Again", left = cancel,
                right = Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 6, 10),
                contentIcon = if (error == RideReplyAvailability.NO_NETWORK) 100 else 110)
        }
    }
}
