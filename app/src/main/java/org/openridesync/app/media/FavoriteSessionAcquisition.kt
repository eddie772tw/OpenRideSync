package org.openridesync.app.media

/** Per-start, memory-only identity fence while waiting for a favorite's playback capability.
 * IDs belong to one NativeMedia owner and survive manager/notification/browser source changes
 * only while that exact session remains present. A lost choice ends this attempt permanently.
 */
internal class FavoriteSessionAcquisition {
    internal sealed interface Decision {
        data object Wait : Decision
        data object StopBrowser : Decision
        data object Unavailable : Decision
        data object Ambiguous : Decision
        data class Select(val id: String) : Decision
    }

    private var pinnedId: String? = null
    private var terminal: Decision? = null
    val foundSession: Boolean get() = pinnedId != null

    fun inspect(ids: List<String>, browserPending: Boolean, deadlineReached: Boolean): Decision {
        terminal?.let { return it }
        val candidates = ids.distinct()
        // Never wait for, or automatically adopt, a replacement after an observed choice vanishes.
        if (pinnedId != null && pinnedId !in candidates) return finish(Decision.Unavailable)
        if (browserPending) return if (deadlineReached) Decision.StopBrowser else Decision.Wait
        if (candidates.size > 1) return finish(Decision.Ambiguous)
        if (candidates.isEmpty()) return if (deadlineReached) finish(Decision.Unavailable) else Decision.Wait
        val chosen = pinnedId ?: candidates.single().also { pinnedId = it }
        return Decision.Select(chosen)
    }

    private fun finish(decision: Decision): Decision { terminal = decision; return decision }
}
