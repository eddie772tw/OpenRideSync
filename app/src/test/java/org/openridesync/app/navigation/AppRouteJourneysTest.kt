package org.openridesync.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Host-executable navigation reducer journeys. These verify route history only: account state,
 * wizard progress, Compose rendering, permissions and network requests belong to other tests.
 */
class AppRouteJourneysTest {
    @Test fun nestedMusicAccountRecoveryRestoresEveryReturnOwnerInOrder() {
        var routes = AppRoutes.initial("deferred")
        for (destination in listOf("ride", "media", "music-apps", "account", "diagnostics")) {
            routes = AppRoutes.open(routes, destination)
        }
        // Restore from an independent saved-name snapshot, then exhaust the whole return path.
        routes = AppRoutes.restore(routes.toList())
        for (owner in listOf("account", "music-apps", "media", "ride", "home")) {
            routes = AppRoutes.back(routes)
            assertEquals("Return owner after nested music/account recovery", owner, routes.last())
        }
        assertEquals(listOf("home"), routes)
    }

    @Test fun rideNotificationOverExistingDiagnosticsDoesNotDuplicateEitherDestination() {
        var routes = AppRoutes.open(AppRoutes.open(AppRoutes.initial("started"), "account"), "diagnostics")
        val diagnosticsOwner = routes.toList()
        routes = AppRoutes.open(routes, "ride")
        routes = AppRoutes.open(routes, "diagnostics")
        assertEquals("An existing diagnostics entry remains its original owner", diagnosticsOwner, routes)
        repeat(3) { routes = AppRoutes.open(routes, "ride") }
        routes = AppRoutes.restore(routes.toList())
        assertEquals(diagnosticsOwner, AppRoutes.back(routes))
        routes = AppRoutes.back(AppRoutes.back(routes))
        assertEquals(listOf("home", "setup", "account"), routes)
        assertEquals(listOf("home", "setup"), AppRoutes.back(routes))
    }

    @Test fun returningToGarageDropsOldRideBranchBeforeAccountRecovery() {
        var routes = AppRoutes.open(AppRoutes.initial("deferred"), "vehicles")
        routes = AppRoutes.open(AppRoutes.open(routes, "ride"), "permissions")
        routes = AppRoutes.back(routes)
        routes = AppRoutes.open(routes, "vehicles")
        assertEquals(listOf("home", "vehicles"), routes)
        routes = AppRoutes.open(AppRoutes.open(routes, "account"), "diagnostics")
        routes = AppRoutes.restore(routes.toList())
        routes = AppRoutes.back(AppRoutes.back(routes))
        assertEquals(listOf("home", "vehicles"), routes)
        assertEquals("Leaving the garage must not resurrect its discarded ride branch",
            listOf("home"), AppRoutes.back(routes))
    }

    @Test fun deferringGuideEnteredThroughSettingsDiscardsAllEarlierOwners() {
        var routes = AppRoutes.initial("deferred")
        for (destination in listOf("settings", "setup", "account", "diagnostics")) {
            routes = AppRoutes.open(routes, destination)
        }
        routes = AppRoutes.back(AppRoutes.back(routes))
        routes = AppRoutes.open(routes, "home")
        routes = AppRoutes.restore(routes.toList())
        routes = AppRoutes.open(routes, "settings")
        assertEquals("Re-entering settings after deferral creates a fresh single owner",
            listOf("home", "settings"), routes)
        routes = AppRoutes.open(routes, "setup")
        routes = AppRoutes.back(routes)
        assertEquals(listOf("home", "settings"), routes)
        assertEquals(listOf("home"), AppRoutes.back(routes))
    }

    @Test fun accountRecoveryAndRepeatedEntryRetainEachFeatureReturnTarget() {
        val featurePaths = listOf(
            listOf("vehicles"), listOf("trips"), listOf("weather"), listOf("media"),
            listOf("media", "music-apps"), listOf("messages"), listOf("calls"),
            listOf("settings", "quick-replies"), listOf("settings", "recognition-language"),
        )
        for (path in featurePaths) {
            val owner = path.fold(AppRoutes.initial("deferred"), AppRoutes::open)
            var routes = AppRoutes.open(owner, "account")
            routes = AppRoutes.open(routes, "diagnostics")
            routes = AppRoutes.back(routes)
            repeat(3) { routes = AppRoutes.open(routes, "account") }
            routes = AppRoutes.restore(routes.toList())
            assertEquals("Only one account entry for $path", owner + "account", routes)
            assertEquals("Account recovery returns to $path", owner, AppRoutes.back(routes))
            // Leaving and retrying account access must use the current feature, not an old branch.
            routes = AppRoutes.open(AppRoutes.back(routes), "account")
            assertEquals(owner, AppRoutes.back(routes))
        }
    }

    @Test fun allThreeDestinationSequencesStayRootedUniqueAndRestorable() {
        // Deliberately enumerate the registered route contract, not account IDs or readiness data.
        // This is an exhaustive reducer invariant, not a claim that every combination has a UI link.
        val destinations = listOf("home", "welcome", "setup", "settings", "customize", "account", "permissions",
            "ride-preview", "media-start-test", "weather-report-test", "handlebar-test", "diagnostics",
            "vehicles", "ride", "trips", "media", "music-apps", "messages", "calls", "weather",
            "quick-replies", "recognition-language")
        for (first in destinations) for (second in destinations) for (third in destinations) {
            var routes = AppRoutes.initial("started")
            for (destination in listOf(first, second, third)) {
                val before = routes.toList()
                routes = AppRoutes.open(routes, destination)
                assertEquals("Requested destination must be current", destination, routes.last())
                assertEquals("home", routes.first())
                assertEquals(routes.size, routes.distinct().size)
                assertEquals(routes, AppRoutes.restore(routes.toList()))
                assertEquals("A repeated tap must not add a Back step", routes, AppRoutes.open(routes, destination))
                val existing = before.indexOf(destination)
                if (existing >= 0) assertEquals(before.take(existing + 1), routes)
                else assertEquals("A new destination must return to its precise prior owner", before, AppRoutes.back(routes))
            }
            repeat(destinations.size) { routes = AppRoutes.back(routes) }
            assertEquals(listOf("home"), routes)
        }
    }
}
