package org.openridesync.app.navigation

/** Only navigation names are saved: never account IDs, permissions, or feature readiness. */
internal object AppRoutes {
    private val allowed = setOf("home", "welcome", "setup", "settings", "account", "permissions",
        "ride-preview", "media-start-test", "weather-report-test", "handlebar-test", "navigation-observation", "diagnostics",
        "vehicles", "ride", "trips", "media", "music-apps", "messages", "calls", "navigation", "weather", "customize",
        "quick-replies", "recognition-language", "trip-recording")

    fun initial(progress: String?): List<String> = when (progress) {
        "started" -> listOf("home", "setup")
        "deferred" -> listOf("home")
        else -> listOf("home", "welcome")
    }

    fun restore(stack: List<String>): List<String> =
        if (stack.isEmpty() || stack.first() != "home" || stack.any { it !in allowed } || stack.distinct().size != stack.size)
            listOf("home") else stack

    fun open(stack: List<String>, destination: String): List<String> {
        val current = restore(stack)
        if (destination !in allowed) return current
        val existing = current.indexOf(destination)
        return if (existing >= 0) current.take(existing + 1) else current + destination
    }

    fun back(stack: List<String>): List<String> = restore(stack).let { if (it.size > 1) it.dropLast(1) else it }
}
