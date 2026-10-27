package org.openridesync.app.media

/** Reject concurrent acquisition rather than queueing a later, unexpected playback request. */
internal class FavoriteStartGate {
    class Ticket internal constructor()
    private var owner: Ticket? = null

    @Synchronized fun acquire(): Ticket? {
        if (owner != null) return null
        return Ticket().also { owner = it }
    }

    /** A repeated/late cleanup cannot release an unrelated newer operation. */
    @Synchronized fun release(ticket: Ticket) {
        if (owner === ticket) owner = null
    }
}
