package org.openridesync.app.runtime

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.honda.PairedVehicleStore
import java.util.UUID
import org.openridesync.app.ui.AppAccess

/** Keeps one user-initiated permission request across rotation; never restores it after process death. */
internal class RideViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    private val session = AuthSession.get()
    val connection = app.ride
    data class PermissionRequest(val id: String)
    private val mutableRequest = MutableStateFlow<PermissionRequest?>(null)
    val permissionRequest = mutableRequest.asStateFlow()
    private var selected: PairedVehicleStore.Vehicle? = null
    private var uid: String? = null
    private var revision = -1L

    init {
        viewModelScope.launch { session.state.collect { if (!ownsRequest()) cancelPermissionRequest() } }
    }

    fun connect(vehicle: PairedVehicleStore.Vehicle) {
        if (mutableRequest.value != null || session.state.value.status != AuthStatus.SIGNED_IN) return
        val bluetoothGranted = RideConnection.permissions().all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (bluetoothGranted && AppAccess.NOTIFICATIONS.granted(app) && AppAccess.LOCATION.granted(app)) {
            connection.connect(vehicle); return
        }
        selected = vehicle
        uid = session.currentUser()?.uid ?: return
        revision = session.revision
        mutableRequest.value = PermissionRequest(UUID.randomUUID().toString())
    }

    fun permissionsResult(id: String?) {
        if (id == null || mutableRequest.value?.id != id) return
        val vehicle = selected?.takeIf { ownsRequest() }
        cancelPermissionRequest()
        // The connection rechecks Bluetooth permission. Notification denial alone is not a gate.
        if (vehicle != null) connection.connect(vehicle)
    }

    fun cancelPermissionRequest() {
        mutableRequest.value = null
        selected = null
        uid = null
    }

    private fun ownsRequest() = uid != null && session.currentUser()?.uid == uid &&
        revision == session.revision && session.state.value.status == AuthStatus.SIGNED_IN
}
