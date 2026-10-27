package org.openridesync.app.vehicles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openridesync.honda.PairedVehicleStore

internal enum class GarageChange { PRIMARY, FORGET }
internal data class GarageIdentity(val uid: String, val revision: Long)

/** Explicit local commit only. Identity is checked again immediately before disk access. */
internal class GarageMutation(
    private val scope: CoroutineScope,
    private val identity: () -> GarageIdentity?,
    private val store: (GarageIdentity) -> PairedVehicleStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null
    @Volatile private var epoch = 0L
    fun cancel() { epoch++; job?.cancel(); job = null }
    fun start(change: GarageChange, mac: String, allowed: () -> Boolean,
        onBusy: () -> Unit, onSuccess: (List<PairedVehicleStore.Vehicle>) -> Unit, onFailure: () -> Unit): Boolean {
        if (job?.isActive == true || !allowed()) return false
        val owner = identity() ?: return false
        val token = ++epoch
        fun current() = epoch == token && identity() == owner
        onBusy()
        job = scope.launch {
            try {
                val result = withContext(io) {
                    VehicleWrites.commit {
                        if (!current()) throw CancellationException()
                        if (!allowed()) throw java.io.IOException("garage operation no longer available")
                        val disk = store(owner)
                        val changed = when (change) {
                            GarageChange.PRIMARY -> disk.setFocus(mac)
                            GarageChange.FORGET -> disk.forget(mac)
                        }
                        if (!changed) throw java.io.IOException("saved vehicle changed")
                        disk.read()
                    }
                }
                if (current()) onSuccess(result)
            } catch (_: CancellationException) {
                // A cancelled owner never publishes a late result; disk IO may already have committed.
            } catch (_: java.io.IOException) {
                if (currentCoroutineContext().isActive && current()) onFailure()
            }
        }
        return true
    }
}
