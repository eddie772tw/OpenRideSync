package org.openridesync.app.calls

import org.openridesync.adapter.MusicDisplayText
import org.openridesync.app.ble.Gen1Projection

/** SAB call pages. The source owner decides whether a button is still actionable. */
internal object CallProjection {
    fun popup(item: CallItem, incomingLabel: String): Gen1Projection.Popup? {
        if (item.kind != CallKind.INCOMING) return null
        val caller = fit(item.caller).ifBlank { fit(incomingLabel) }
        return Gen1Projection.Popup(16, caller,
            left = if (CallAction.DECLINE in item.actions)
                Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 12, 2)
            else Gen1Projection.Action(),
            right = if (CallAction.ANSWER in item.actions)
                Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 13, 3)
            else Gen1Projection.Action())
    }

    fun menu(item: CallItem, header: String, first: Boolean, last: Boolean): Gen1Projection.Menu =
        Gen1Projection.Menu(8, 7, header = fit(header),
            up = if (first) null else Gen1Projection.Arrow.UP,
            down = if (last) null else Gen1Projection.Arrow.DOWN,
            right = Gen1Projection.Arrow.RIGHT_BIG,
            contentIcon = if (item.caller.isBlank()) 93 else null,
            line1 = item.caller.takeIf { it.isNotBlank() }?.let(::fit))

    fun live(item: CallItem, incomingHeader: String, ongoingHeader: String): Gen1Projection.Live {
        val incoming = item.kind == CallKind.INCOMING
        return Gen1Projection.Live(8, 9,
            header = fit(if (incoming) incomingHeader else ongoingHeader),
            contentIcon = if (item.caller.isBlank()) if (incoming) 107 else 93 else null,
            line1 = item.caller.takeIf { it.isNotBlank() }?.let(::fit),
            left = when {
                incoming && CallAction.DECLINE in item.actions ->
                    Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 12, 2)
                !incoming && CallAction.HANG_UP in item.actions ->
                    Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 2, 2)
                else -> Gen1Projection.Action()
            },
            right = if (incoming && CallAction.ANSWER in item.actions)
                Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 13, 3)
            else Gen1Projection.Action())
    }

    fun cancel(header: String): Gen1Projection.Live = Gen1Projection.Live(8, 9,
        header = fit(header), contentIcon = 106,
        left = Gen1Projection.Action(Gen1Projection.Arrow.LEFT, 15, 7),
        right = Gen1Projection.Action(Gen1Projection.Arrow.RIGHT, 9, 7))

    private fun fit(raw: String): String {
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
