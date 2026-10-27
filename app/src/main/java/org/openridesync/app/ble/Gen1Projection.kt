package org.openridesync.app.ble

import org.openridesync.gen1.ScreenEncoder

/** Resolved page values only: providers/localization, mode and publication lifetime belong to caller.
 * Mirrors ScreenProjector p/o/q; the transport queue alone supplies request IDs. */
internal object Gen1Projection {
    const val WHITE = 0
    const val BLACK = 1
    sealed interface Page
    data class Arrow(val id: Int, val tint: Int = BLACK) {
        companion object {
            val LEFT = Arrow(1)
            val RIGHT = Arrow(2)
            val UP = Arrow(3)
            val DOWN = Arrow(4)
            val RIGHT_BIG = Arrow(5, WHITE)
        }
    }
    data class Action(val arrow: Arrow? = null, val contentIcon: Int? = null, val background: Int? = null)
    data class Menu(
        val color: Int, val colorLight: Int, val header: String? = null,
        val up: Arrow? = null, val down: Arrow? = null, val left: Arrow? = null, val right: Arrow? = null,
        val contentIcon: Int? = null, val line1: String? = null, val line2: String? = null,
    ) : Page
    data class Live(
        val color: Int, val colorDark: Int, val header: String? = null,
        val left: Action = Action(), val right: Action = Action(),
        val contentIcon: Int? = null, val line1: String? = null, val line2: String? = null,
    ) : Page
    data class Popup(
        val background: Int, val text: String, val contentIcon: Int? = null,
        val left: Action = Action(), val right: Action = Action(),
    ) : Page

    /** An absent capability is not permission to publish; extra future capability bits are retained. */
    fun supports(page: Page, capabilityBits: Int?): Boolean {
        if (capabilityBits == null) return false
        require(capabilityBits in 0..255)
        return capabilityBits and (if (page is Popup) 2 else 4) != 0
    }

    fun encode(page: Page, requestId: Int): ByteArray {
        val bytes = when (page) {
            is Menu -> ScreenEncoder.menu(requestId, page.color, text(page.header), page.colorLight,
                arrow(page.up), arrow(page.down), arrow(page.left), arrow(page.right),
                content(page.contentIcon), text(page.line1), text(page.line2))
            is Live -> ScreenEncoder.live(requestId, page.color, text(page.header), page.color,
                action(page.left), action(page.right), page.colorDark, content(page.contentIcon),
                text(page.line1), text(page.line2))
            is Popup -> ScreenEncoder.popup(requestId, page.background, content(page.contentIcon), text(page.text, 90),
                action(page.left), action(page.right))
        }
        // Core encoder deliberately mirrors OEM length wrapping; native callers must not wrap.
        require(bytes.size <= 512) { "Projection exceeds characteristic limit" }
        return bytes
    }

    /** RS-TEXT-001/002: remove only the recovered bidi controls, then fit whole Unicode scalars.
     * Phone content and speech retain their original strings; this is a vehicle display budget. */
    fun prepareText(value: String?, budget: Int = 30): String {
        require(budget in 0..90)
        if (value == null) return ""
        val result = StringBuilder()
        var size = 0
        var offset = 0
        while (offset < value.length) {
            val point = value.codePointAt(offset)
            offset += Character.charCount(point)
            if (point in 0x202a..0x202e || point in 0x2066..0x2069 ||
                point == 0x200e || point == 0x200f || point == 0x061c) continue
            val scalar = String(Character.toChars(point))
            val count = scalar.toByteArray(Charsets.UTF_8).size
            if (size + count > budget) break
            result.append(scalar)
            size += count
        }
        return result.toString()
    }

    private fun text(value: String?, budget: Int = 30): ScreenEncoder.Text {
        val bytes = prepareText(value, budget).toByteArray(Charsets.UTF_8)
        return ScreenEncoder.Text(bytes, if (value == null) -1 else WHITE)
    }
    private fun content(id: Int?) = ScreenEncoder.Icon(id ?: 0, if (id == null || id == 0) -1 else WHITE)
    private fun arrow(value: Arrow?) = ScreenEncoder.Icon(value?.id ?: 0, value?.tint ?: -1)
    private fun action(value: Action) = ScreenEncoder.Action(value.arrow?.id ?: 0, value.arrow?.tint ?: -1,
        value.contentIcon ?: -1, if (value.arrow == null) -1 else WHITE,
        if (value.arrow == null) -1 else value.background ?: -1)
}
