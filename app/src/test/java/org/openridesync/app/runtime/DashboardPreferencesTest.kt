package org.openridesync.app.runtime

import org.junit.Assert.*
import org.junit.Test
import org.openridesync.app.runtime.MainMenuSelection.Item

class DashboardPreferencesTest {
    @Test fun storedOrderNormalizesUnknownDuplicatesAndMissingFeatures() {
        assertEquals(Item.entries, parseFunctionOrder(null))
        assertEquals(Item.entries, parseFunctionOrder(""))
        val order = parseFunctionOrder(" VOLUME,MUSIC,UNKNOWN,VOLUME ")
        assertEquals(listOf(Item.VOLUME, Item.MUSIC, Item.WEATHER, Item.NAVIGATION, Item.CALL, Item.MESSAGE), order)
        assertEquals(order, parseFunctionOrder(order.joinToString(",") { it.name }))
    }

    @Test fun movementIsBoundedAndDoesNotMutateSource() {
        val order = Item.entries.toList()
        assertEquals(order, moveFunction(order, Item.WEATHER, -1))
        assertEquals(order, moveFunction(order, Item.VOLUME, 1))
        assertEquals(order, moveFunction(order, Item.MUSIC, 10))
        val moved = moveFunction(order, Item.MUSIC, -1)
        assertEquals(Item.MUSIC, moved[3])
        assertEquals(Item.MESSAGE, moved[4])
        assertEquals(Item.entries, order)
        assertEquals(Item.entries.toSet(), moved.toSet())
    }

    @Test fun reorderedFirstLoadFindsSupportedProviderEvenBeforeDefaultFocus() {
        val menu = MainMenuSelection(2)
        menu.update(Item.entries.reversed().map { MainMenuSelection.Entry(it, it == Item.MUSIC || it == Item.VOLUME) })
        assertEquals(Item.VOLUME, menu.selected?.item)
        assertTrue(menu.selected!!.first)
        menu.next()
        assertEquals(Item.MUSIC, menu.selected?.item)
        assertTrue(menu.selected!!.last)
    }

    @Test fun reorderedSelectionRetainsIdentityAndRecomputesArrowEnds() {
        val menu = MainMenuSelection(4)
        menu.update(Item.entries.map { MainMenuSelection.Entry(it, it == Item.MUSIC || it == Item.VOLUME) })
        assertEquals(MainMenuSelection.Selected(Item.MUSIC, true, false), menu.selected)
        menu.update(Item.entries.reversed().map { MainMenuSelection.Entry(it, it == Item.MUSIC || it == Item.VOLUME) })
        assertEquals(MainMenuSelection.Selected(Item.MUSIC, false, true), menu.selected)
    }

    @Test fun hiddenFocusedMusicFallsBackToEarlierVolumeInCustomOrder() {
        val order = Item.entries.reversed()
        val menu = MainMenuSelection(4)
        menu.update(order.map { MainMenuSelection.Entry(it, it == Item.MUSIC || it == Item.VOLUME) })
        assertEquals(Item.MUSIC, menu.selected?.item)
        menu.update(order.map { MainMenuSelection.Entry(it, it == Item.VOLUME) })
        assertEquals(MainMenuSelection.Selected(Item.VOLUME, true, true), menu.selected)
        menu.previous()
        menu.next()
        assertEquals(Item.VOLUME, menu.selected?.item)
        menu.update(order.map { MainMenuSelection.Entry(it, false) })
        assertNull(menu.selected)
    }

    @Test fun hidingLastMusicFallsBackToFirstWeatherAndRecomputesArrows() {
        val order = listOf(Item.WEATHER, Item.VOLUME, Item.NAVIGATION, Item.CALL, Item.MESSAGE, Item.MUSIC)
        val menu = MainMenuSelection(4)
        val visible = setOf(Item.WEATHER, Item.VOLUME, Item.MUSIC)
        menu.update(order.map { MainMenuSelection.Entry(it, it in visible) })
        assertEquals(Item.MUSIC, menu.selected?.item)
        menu.update(order.map { MainMenuSelection.Entry(it, it in visible && it != Item.MUSIC) })
        assertEquals(MainMenuSelection.Selected(Item.WEATHER, true, false), menu.selected)
        menu.next()
        assertEquals(MainMenuSelection.Selected(Item.VOLUME, false, true), menu.selected)
    }

    @Test fun weatherSpeechParsesSafelyAndSummaryIsExactlyIntroAndFirstPeriod() {
        assertEquals(WeatherSpeechMode.FULL, WeatherSpeechMode.parse(null))
        assertEquals(WeatherSpeechMode.FULL, WeatherSpeechMode.parse("broken"))
        WeatherSpeechMode.entries.forEach { assertEquals(it, WeatherSpeechMode.parse(it.name)) }
        val lines = listOf("Place", "First forecast", "Later forecast", "Last forecast")
        assertEquals(lines, weatherSpeechLines(lines, WeatherSpeechMode.FULL))
        assertEquals(lines.take(2), weatherSpeechLines(lines, WeatherSpeechMode.SUMMARY))
        assertTrue(weatherSpeechLines(lines, WeatherSpeechMode.OFF).isEmpty())
        assertTrue(weatherSpeechLines(emptyList(), WeatherSpeechMode.SUMMARY).isEmpty())
    }
}
