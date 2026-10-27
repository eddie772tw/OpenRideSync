package org.openridesync.app.navigation

import org.junit.Assert.*
import org.junit.Test

class MainTabsTest {
    @Test fun exactFivePrimaryDestinations() {
        assertEquals(listOf("home", "trips", "vehicles", "customize", "settings"), MainTab.entries.map { it.route })
    }
    @Test fun selectingTabsCreatesOneOwnerAndNeverAccumulatesTabHistory() {
        for (tab in MainTab.entries) {
            var routes = listOf("home", "vehicles", "ride", "account", "diagnostics")
            repeat(4) { routes = MainTab.select(routes, tab.route) }
            assertEquals(if (tab == MainTab.HOME) listOf("home") else listOf("home",tab.route), routes)
            assertEquals(listOf("home"), AppRoutes.back(routes))
            assertEquals(tab, MainTab.selected(AppRoutes.restore(routes)))
        }
    }
    @Test fun nestedBackKeepsItsTabOwnerAcrossRestorationAndNotificationEntry() {
        for (tab in MainTab.entries) {
            val owner = MainTab.select(listOf("home"),tab.route)
            var routes = AppRoutes.open(AppRoutes.open(owner,"account"),"diagnostics")
            routes = AppRoutes.open(routes,"ride")
            assertEquals(tab, MainTab.selected(AppRoutes.restore(routes.toList())))
            repeat(3) { routes = AppRoutes.back(routes) }
            assertEquals(owner,routes)
        }
    }
    @Test fun invalidPrimaryInputCannotOpenToolsOrBypassWelcome() {
        val welcome = AppRoutes.initial(null)
        assertEquals(welcome, MainTab.select(welcome,"media-start-test"))
        assertFalse(MainTab.visible("welcome"));assertFalse(MainTab.visible("setup"))
        assertFalse(MainTab.visible("ride-preview"));assertTrue(MainTab.visible("account"))
        assertEquals(MainTab.HOME, MainTab.selected(listOf("invalid")))
    }
}
