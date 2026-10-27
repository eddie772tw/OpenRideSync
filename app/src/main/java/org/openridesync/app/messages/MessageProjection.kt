package org.openridesync.app.messages

import org.openridesync.adapter.MusicDisplayText
import org.openridesync.app.ble.Gen1Projection
import org.openridesync.app.runtime.MessageReadStatus
import org.openridesync.app.runtime.RidePageLabels

/** Native message pages. Notification bodies never enter a vehicle frame. */
internal object MessageProjection {
    /** Modern native text policy; capability4 alone does not establish OEM modernDisplayEnabled. */
    fun incoming(item: MessageItem, title: String, canPlay: Boolean, reading: Boolean,
        canReply: Boolean = false): Gen1Projection.Live {
        val label = fit(item.sender ?: item.title, 240)
        val first = fit(label, 120)
        val second = label.substring(first.length).takeIf { it.isNotEmpty() }
        return Gen1Projection.Live(11, 12, header = fit(title, 100),
            left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, if (canPlay || reading) 8 else 1,
                if (canPlay || reading) 10 else 2),
            right = if (canPlay) Gen1Projection.Action(Gen1Projection.Arrow.RIGHT_BIG, 3, 3)
                else if (reading && canReply && item.canReply) Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 14, 3)
                else Gen1Projection.Action(),
            line1 = first, line2 = second)
    }

    fun menu(title: String, first: Boolean, last: Boolean) = Gen1Projection.Menu(11, 10,
        header = fit(title, 120),
        up = if (first) null else Gen1Projection.Arrow.UP,
        down = if (last) null else Gen1Projection.Arrow.DOWN,
        right = Gen1Projection.Arrow.RIGHT,
        contentIcon = 4)

    fun browse(items: List<MessageItem>, selectedId: String?, labels: RidePageLabels,
               status: MessageReadStatus): Gen1Projection.Menu {
        val index = items.indexOfFirst { it.id == selectedId }
        val item = items.getOrNull(index)
        val header = when (status) {
            MessageReadStatus.READING -> labels.messageReading
            MessageReadStatus.COMPLETE -> if (item?.canReply == true) labels.messageReply else labels.messageComplete
            MessageReadStatus.FAILED -> labels.messageFailed
            MessageReadStatus.NONE -> labels.messages
        }
        return Gen1Projection.Menu(11, 10, header = fit(header, 100),
            up = if (index > 0) Gen1Projection.Arrow.UP else null,
            down = if (index >= 0 && index < items.lastIndex) Gen1Projection.Arrow.DOWN else null,
            left = Gen1Projection.Arrow.LEFT,
            right = if (item != null && status != MessageReadStatus.READING)
                Gen1Projection.Arrow.RIGHT_BIG else null,
            contentIcon = 4,
            line1 = fit(item?.title ?: labels.noMessages, 150),
            line2 = item?.appName?.let { fit(it, 100) })
    }

    /** Bound UTF-8 at Unicode scalar boundaries; every field and frame stays below Gen1 limits. */
    private fun fit(raw: String, limit: Int): String {
        val text = MusicDisplayText.prepare(raw)
        var offset = 0
        var bytes = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            val size = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) break
            bytes += size
            offset += Character.charCount(point)
        }
        return text.substring(0, offset)
    }
}
