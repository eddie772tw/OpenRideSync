package org.openridesync.app.navigation

/** Primary destinations are UI names only. Tab changes never imply feature reads or readiness. */
internal enum class MainTab(val route: String, val label: String) {
    HOME("home", "首頁"), TRIPS("trips", "旅程查詢"), GARAGE("vehicles", "車庫管理"),
    CUSTOMIZE("customize", "儀表自訂"), SETTINGS("settings", "設定");

    companion object {
        fun selected(stack: List<String>): MainTab = AppRoutes.restore(stack).asReversed()
            .firstNotNullOfOrNull { route -> entries.firstOrNull { it.route == route } } ?: HOME
        fun select(stack: List<String>, destination: String): List<String> {
            val target = entries.firstOrNull { it.route == destination } ?: return AppRoutes.restore(stack)
            return if (target == HOME) listOf("home") else listOf("home", target.route)
        }
        fun visible(destination: String) = destination !in setOf("welcome", "setup", "ride-preview",
            "handlebar-test", "media-start-test", "weather-report-test")
    }
}
