package org.openridesync.app.runtime

/** One application-wide vehicle operation; stale completion cannot release a newer owner. */
internal class VehicleOperationGate {
    enum class Kind { PAIRING, RUNTIME }
    class Ticket internal constructor(val kind: Kind)
    private var owner: Ticket? = null

    @Synchronized fun tryAcquire(kind: Kind): Ticket? {
        if (owner != null) return null
        return Ticket(kind).also { owner = it }
    }

    @Synchronized fun release(ticket: Ticket) {
        if (owner === ticket) owner = null
    }
}
