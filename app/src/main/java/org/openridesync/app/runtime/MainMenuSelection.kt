package org.openridesync.app.runtime

/** App-configurable ordering/selection. Providers still decide visibility and real actions. */
internal class MainMenuSelection(private val initialIndex: Int) {
    enum class Item { WEATHER, NAVIGATION, CALL, MESSAGE, MUSIC, VOLUME }
    data class Entry(val item: Item, val visible: Boolean)
    data class Selected(val item: Item, val first: Boolean, val last: Boolean)

    var index: Int = initialIndex
        private set
    private var entries: List<Entry> = emptyList()

    init { require(initialIndex in Item.entries.indices) }

    val selected: Selected?
        get() {
            val entry = entries.getOrNull(index)?.takeIf { it.visible } ?: return null
            return Selected(entry.item, entries.indexOfFirst { it.visible } == index,
                entries.indexOfLast { it.visible } == index)
        }

    fun update(items: List<Entry>) {
        require(items.size == Item.entries.size && items.map { it.item }.toSet().size == Item.entries.size)
        val firstLoad = entries.isEmpty()
        val focused = entries.getOrNull(index)?.item ?: Item.entries[initialIndex]
        entries = items.toList()
        index = entries.indexOfFirst { it.item == focused }
        if (firstLoad && items.map { it.item } != Item.entries && selected == null) {
            // A custom order may put every supported provider before the default focus.
            index = entries.indexOfFirst { it.visible }.takeIf { it >= 0 } ?: index
        }
        repairSelection()
    }

    private fun repairSelection() {
        if (selected != null) return
        next()
        // Availability changes can hide the final visible item in a custom order.
        // Keep a usable menu whenever any real provider remains; user movement still never wraps.
        if (selected == null) index = entries.indexOfFirst { it.visible }.takeIf { it >= 0 } ?: index
    }

    fun previous() {
        for (candidate in index - 1 downTo 0) if (entries.getOrNull(candidate)?.visible == true) {
            index = candidate
            return
        }
    }

    fun next() {
        for (candidate in index + 1 until entries.size) if (entries[candidate].visible) {
            index = candidate
            return
        }
    }

    /** An explicit MainPage resume argument is a menu item, resolved through the current order. */
    fun resume(item: Item) {
        val target = entries.indexOfFirst { it.item == item }
        check(target >= 0) { "Menu items are not loaded" }
        index = target
        repairSelection()
    }
}

/** Unknown/duplicate stored values are ignored; newly added features retain their default order. */
internal fun parseFunctionOrder(stored: String?): List<MainMenuSelection.Item> = normalizeFunctionOrder(
    stored.orEmpty().split(',').mapNotNull { token -> MainMenuSelection.Item.entries.find { it.name == token.trim() } })

internal fun normalizeFunctionOrder(items: List<MainMenuSelection.Item>): List<MainMenuSelection.Item> =
    (items + MainMenuSelection.Item.entries).distinct()

internal fun moveFunction(items: List<MainMenuSelection.Item>, item: MainMenuSelection.Item, delta: Int): List<MainMenuSelection.Item> {
    val order = normalizeFunctionOrder(items).toMutableList()
    val from = order.indexOf(item)
    val to = from + delta
    if (delta !in listOf(-1, 1) || to !in order.indices) return order
    order.removeAt(from)
    order.add(to, item)
    return order
}
