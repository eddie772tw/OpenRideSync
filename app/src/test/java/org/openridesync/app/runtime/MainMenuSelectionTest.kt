package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.runtime.MainMenuSelection.Item

class MainMenuSelectionTest {
    private fun entries(visible: Set<Item>, order: List<Item> = Item.entries) =
        order.map { MainMenuSelection.Entry(it, it in visible) }

    @Test fun movementSkipsHiddenItemsClampsAndDerivesVisibleArrowEnds() {
        val menu = MainMenuSelection(1)
        menu.update(entries(setOf(Item.NAVIGATION, Item.MUSIC, Item.VOLUME)))
        assertEquals(MainMenuSelection.Selected(Item.NAVIGATION, true, false), menu.selected)
        menu.previous()
        assertEquals(1, menu.index)
        menu.next()
        assertEquals(Item.MUSIC, menu.selected?.item)
        menu.next()
        menu.next()
        assertEquals(MainMenuSelection.Selected(Item.VOLUME, false, true), menu.selected)
        menu.previous()
        assertEquals(4, menu.index)
    }

    @Test fun visibilityChangeRepairsSelectionAndExplicitResumeResolvesCurrentOrder() {
        val menu = MainMenuSelection(2)
        val visible = setOf(Item.CALL, Item.MUSIC, Item.VOLUME)
        menu.update(entries(visible))
        menu.update(entries(visible - Item.CALL))
        assertEquals(Item.MUSIC, menu.selected?.item)
        val reversed = Item.entries.reversed()
        menu.update(entries(visible, reversed))
        assertEquals(Item.MUSIC, menu.selected?.item) // Reordering preserves the focused feature identity.
        menu.resume(Item.VOLUME)
        assertEquals(MainMenuSelection.Selected(Item.VOLUME, true, false), menu.selected)
        menu.update(entries(emptySet(), reversed))
        assertNull(menu.selected)
    }

    @Test fun rejectsIncompleteOrDuplicateOrdersBeforeChangingSelection() {
        val menu = MainMenuSelection(0)
        val items = entries(Item.entries.toSet())
        menu.update(items)
        assertThrows(IllegalArgumentException::class.java) { menu.update(items.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { menu.update(List(6) { items.first() }) }
        assertEquals(Item.WEATHER, menu.selected?.item)
        assertThrows(IllegalArgumentException::class.java) { MainMenuSelection(-1) }
    }
}
