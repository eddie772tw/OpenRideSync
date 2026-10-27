package org.openridesync.app.navigation

import org.junit.Assert.*
import org.junit.Test

class AppRoutesTest {
    @Test fun firstLaunchAndUnknownProgressShowIntroduction() {
        assertEquals(listOf("home", "welcome"), AppRoutes.initial(null))
        assertEquals(listOf("home", "welcome"), AppRoutes.initial("invalid"))
    }
    @Test fun startedGuideResumesButDeferredGuideDoesNotBlockHome() {
        assertEquals(listOf("home", "setup"), AppRoutes.initial("started"))
        assertEquals(listOf("home"), AppRoutes.initial("deferred"))
    }
    @Test fun accountAndPermissionsReturnToInitiatingGuide() {
        val setup = listOf("home", "setup")
        for (route in listOf("account", "permissions", "vehicles")) assertEquals(setup, AppRoutes.back(AppRoutes.open(setup, route)))
    }
    @Test fun settingsAndNestedMusicReturnToTheirOwners() {
        assertEquals(listOf("home", "settings"), AppRoutes.back(AppRoutes.open(listOf("home", "settings"), "account")))
        assertEquals(listOf("home", "ride", "media"), AppRoutes.back(listOf("home", "ride", "media", "music-apps")))
    }
    @Test fun repeatedNotificationsAndAncestorNavigationDoNotAccumulateLoops() {
        val ride = listOf("home", "ride")
        assertEquals(ride, AppRoutes.open(AppRoutes.open(ride, "diagnostics"), "ride"))
        assertEquals(ride, AppRoutes.open(ride, "ride"))
        assertEquals(listOf("home"), AppRoutes.open(listOf("home", "setup", "account"), "home"))
    }
    @Test fun notificationCanOpenRideWithoutConsumingIntroduction() {
        val initial = AppRoutes.initial(null)
        assertEquals(initial, AppRoutes.back(AppRoutes.open(initial, "ride")))
    }
    @Test fun restoredStateRejectsUnknownDuplicateOrUnrootedRoutes() {
        for (bad in listOf(emptyList(), listOf("account"), listOf("home", "bogus"), listOf("home", "home"))) {
            assertEquals(listOf("home"), AppRoutes.restore(bad))
        }
        assertEquals(listOf("home", "settings", "permissions"), AppRoutes.restore(listOf("home", "settings", "permissions")))
    }
    @Test fun rootBackAndUnknownDestinationAreHarmless() {
        assertEquals(listOf("home"), AppRoutes.back(listOf("home")))
        assertEquals(listOf("home", "setup"), AppRoutes.open(listOf("home", "setup"), "unknown"))
    }
}
