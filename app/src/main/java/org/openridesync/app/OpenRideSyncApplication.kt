package org.openridesync.app

import android.app.Application
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.io.File
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.runtime.RideConnection
import org.openridesync.app.runtime.VehicleOperationGate

class OpenRideSyncApplication : Application() {
    internal lateinit var homeWeather: org.openridesync.app.weather.HomeWeather
        private set
    internal val vehicleOperations = VehicleOperationGate()
    internal lateinit var ride: RideConnection
        private set
    internal lateinit var ridePreferences: org.openridesync.app.runtime.RidePreferences
        private set
    internal lateinit var quickReplyPreferences: org.openridesync.app.runtime.QuickReplyPreferences
        private set
    internal lateinit var recognitionLanguages: org.openridesync.app.runtime.RecognitionLanguages
        private set
    internal lateinit var tripRecording: org.openridesync.app.trips.TripRecording
        private set
    var journal: DiagnosticJournal? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // Diagnostics starts before any screen or optional SDK. Failure remains visible in UI.
        journal = runCatching { DiagnosticJournal(File(filesDir, "diagnostics")) }.getOrNull()
        journal?.record(Component.APP, null, Event.START, Outcome.OBSERVED, ErrorCode.NONE)
        AuthSession.init(this, journal)
        ridePreferences = org.openridesync.app.runtime.RidePreferences(this)
        quickReplyPreferences = org.openridesync.app.runtime.QuickReplyPreferences(this)
        recognitionLanguages = org.openridesync.app.runtime.RecognitionLanguages(this,
            country = AuthSession.get().countryOfResidence)
        ride = RideConnection(this)
        tripRecording = org.openridesync.app.trips.TripRecording(this)
        homeWeather = org.openridesync.app.weather.HomeWeather(this)
    }
}
