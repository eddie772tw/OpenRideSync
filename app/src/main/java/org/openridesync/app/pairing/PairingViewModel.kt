package org.openridesync.app.pairing

import android.app.Application
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.app.ble.BtuInformation
import org.openridesync.app.ble.GattSession
import org.openridesync.app.ble.GattFailure
import org.openridesync.app.honda.HondaSessionRead
import org.openridesync.app.runtime.VehicleOperationGate
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.wire.HondaWire.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class PairingStage { IDLE, PERMISSION_REQUIRED, CHECKING, CHECKED, CHOOSING, BONDING, READING, RESOLVING, MODEL_CHOICE, SAVING, PAIRED, FAILED, CANCELLED }
internal fun pairingSupported(generation: Int) = generation in 1..2
internal data class PairingState(
    val stage: PairingStage = PairingStage.IDLE,
    val attempt: String? = null,
    val chooserReady: Boolean = false,
    val candidates: List<Model> = emptyList(),
    val vehicleName: String? = null,
    val problem: String? = null,
)

/** Explicit foreground pairing only. PAIRED means local enrollment, never READY or security completion. */
internal class PairingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    val session = AuthSession.get()
    private val mutableState = MutableStateFlow(PairingState())
    val state = mutableState.asStateFlow()
    private val bluetooth = PairingBluetooth(app, app.journal)
    private var job: Job? = null
    private var operationTicket: VehicleOperationGate.Ticket? = null
    private var choice: CompanionChoice? = null
    private var sender: IntentSender? = null
    // Retain the one launched picker through cancellation so a late API30–32 result
    // still reaches its closed helper and can clean up that attempt's association.
    private var launchedChoice: Pair<String, CompanionChoice>? = null
    private var permissionOffered = false
    private var selected: Model? = null
    private var year = 0
    private var uid: String? = null
    private var revision = -1L
    private var modelChoice: CompletableDeferred<Model>? = null
    private var foreground = false
    private var pausedScan = false
    private var scanEpoch = 0L

    init {
        viewModelScope.launch {
            session.state.collect {
                if (state.value.stage != PairingStage.IDLE && !identity()) clear()
            }
        }
    }

    private fun identity() = uid != null && session.currentUser()?.uid == uid && session.revision == revision &&
        session.state.value.status == AuthStatus.SIGNED_IN
    private fun current(attempt: String) = state.value.attempt == attempt && identity()
    private fun checkCurrent(attempt: String) { if (!current(attempt)) throw CancellationException() }

    fun start(model: Model, selectedYear: Int) {
        if (session.state.value.status != AuthStatus.SIGNED_IN || state.value.stage != PairingStage.IDLE) return
        uid = session.currentUser()?.uid ?: return
        revision = session.revision
        selected = model
        year = selectedYear.takeIf { it in model.modelYearsList } ?: 0
        permissionOffered = false
        val attempt = app.journal?.newOperation() ?: UUID.randomUUID().toString()
        if (!pairingSupported(model.meter.generation)) {
            mutableState.value = PairingState(PairingStage.FAILED, attempt, problem = "UNSUPPORTED")
            app.journal?.record(Component.UI, attempt, Event.REQUEST, Outcome.FAILED, ErrorCode.UNSUPPORTED)
            return
        }
        operationTicket = app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING)
        if (operationTicket == null) {
            mutableState.value = PairingState(PairingStage.FAILED, attempt, problem = "OPERATION_BUSY")
            return
        }
        mutableState.value = PairingState(PairingStage.PERMISSION_REQUIRED, attempt)
        foreground = true // Explicit click in the visible model/SETUP screen.
        pausedScan = false
        if (permissionsGranted()) checkAdvertisement(attempt)
    }

    fun takePermissionRequest(attempt: String): Boolean {
        if (!current(attempt) || state.value.stage != PairingStage.PERMISSION_REQUIRED || permissionOffered) return false
        permissionOffered = true
        return true
    }
    fun permissionsResult(attempt: String?) {
        if (attempt == null || !current(attempt) || state.value.stage != PairingStage.PERMISSION_REQUIRED) return
        if (permissionsGranted()) checkAdvertisement(attempt)
        else mutableState.value = state.value.copy(problem = "PERMISSION_REQUIRED")
    }
    fun requestPermissionsAgain() { permissionOffered = false; mutableState.value = state.value.copy(problem = null) }
    private fun permissionsGranted() = PairingBluetooth.permissions().all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun checkAdvertisement(attempt: String) {
        val epoch = ++scanEpoch
        mutableState.value = state.value.copy(stage = PairingStage.CHECKING, problem = null)
        pausedScan = !foreground
        if (!foreground) return
        val previous = job
        launchAttempt(attempt) {
            try {
                previous?.cancelAndJoin() // The old scanner's finally must finish before another startScan.
                ensureActive()
                if (epoch != scanEpoch || !foreground || !current(attempt)) return@launchAttempt
                bluetooth.awaitAdvertisement(attempt)
                ensureActive()
                if (epoch != scanEpoch || !foreground) return@launchAttempt
                checkCurrent(attempt)
                mutableState.value = state.value.copy(stage = PairingStage.CHECKED)
            } catch (error: Exception) {
                if (epoch == scanEpoch && foreground) failed(attempt, error)
            }
        }
    }

    fun onStart() {
        foreground = true
        if (!pausedScan || state.value.stage != PairingStage.CHECKING) return
        val attempt = state.value.attempt ?: return
        if (!current(attempt)) { clear(); return }
        pausedScan = false
        if (permissionsGranted()) checkAdvertisement(attempt)
        else {
            permissionOffered = false
            mutableState.value = state.value.copy(stage = PairingStage.PERMISSION_REQUIRED, problem = null)
        }
    }

    fun onStop() {
        foreground = false
        if (state.value.stage !in setOf(PairingStage.CHECKING, PairingStage.CHECKED)) return
        scanEpoch++
        pausedScan = true
        job?.cancel()
        mutableState.value = state.value.copy(stage = PairingStage.CHECKING, problem = null)
    }

    fun retry() {
        val model = selected ?: return
        val savedYear = year
        clear()
        start(model, savedYear)
    }

    @SuppressLint("MissingPermission") // Native helpers check permissions; revocation is handled as failure.
    fun chooseDevice() {
        val attempt = state.value.attempt ?: return
        if (!current(attempt) || state.value.stage != PairingStage.CHECKED) return
        if (launchedChoice != null) {
            mutableState.value = state.value.copy(stage = PairingStage.FAILED, problem = "CHOOSER_FAILED")
            releaseAfterJob()
            return
        }
        val ownerUid = uid ?: return
        val ownerRevision = revision
        mutableState.value = state.value.copy(stage = PairingStage.CHOOSING)
        launchAttempt(attempt) {
            val association = CompanionChoice(app, app.journal, attempt)
            choice = association
            try {
                val device = association.await { ready ->
                    viewModelScope.launch {
                        if (current(attempt) && choice === association && state.value.stage == PairingStage.CHOOSING) {
                            sender = ready
                            mutableState.value = state.value.copy(chooserReady = true)
                        }
                    }
                }
                checkCurrent(attempt)
                mutableState.value = state.value.copy(stage = PairingStage.BONDING, chooserReady = false)
                bluetooth.bond(device, attempt)
                checkCurrent(attempt)
                mutableState.value = state.value.copy(stage = PairingStage.READING)
                val journal = checkNotNull(app.journal)
                val info = recordStep(attempt, Event.BLE_BTU_INFORMATION) {
                    withTimeout(30_000) {
                        withContext(Dispatchers.IO) {
                            GattSession(app, device, journal, attempt).use { connection ->
                                connection.connect(); connection.discoverServices()
                                BtuInformation.read(connection).also { require(!it.seriesCode.isNullOrBlank()) }
                            }
                        }
                    }
                }
                checkCurrent(attempt)
                mutableState.value = state.value.copy(stage = PairingStage.RESOLVING)
                val seriesCode = checkNotNull(info.seriesCode)
                val models = runInterruptible(Dispatchers.IO) {
                    HondaSessionRead(session, app.journal, attempt, ownerUid, ownerRevision).use { reader ->
                        reader.read(Event.HONDA_LIST_MODELS) { client, key, token ->
                            // OEM's Gen1 route requests generations 1.0/1.5. Generation 2.0
                            // is only added when its separate runtime handoff is available.
                            client.listModels(ListModelsRequest.newBuilder().addAllMeterGenerations(listOf(1, 2))
                                .addSeriesCodes(seriesCode).build(), key, token).modelsList
                        }
                    }
                }
                checkCurrent(attempt)
                require(models.map { it.modelLabel }.distinct().size == models.size && models.all { it.modelLabel.isNotBlank() })
                val resolved = when (models.size) {
                    0 -> checkNotNull(selected)
                    1 -> models.single()
                    else -> {
                        val decision = CompletableDeferred<Model>()
                        modelChoice = decision
                        mutableState.value = state.value.copy(stage = PairingStage.MODEL_CHOICE, candidates = models)
                        decision.await().also { modelChoice = null }
                    }
                }
                checkCurrent(attempt)
                if (!pairingSupported(resolved.meter.generation)) throw PairingBluetooth.Failure(PairingBluetooth.Problem.UNSUPPORTED)
                mutableState.value = state.value.copy(stage = PairingStage.SAVING, candidates = emptyList())
                val resolvedYear = if (resolved.modelLabel == selected?.modelLabel && year in resolved.modelYearsList) year else 0
                withContext(Dispatchers.IO) {
                    writes.withLock {
                        ensureActive(); checkCurrent(attempt)
                        val hash = MessageDigest.getInstance("SHA-256").digest(ownerUid.toByteArray())
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        recordStep(attempt, Event.PAIRED_VEHICLE_SAVE) {
                            PairedVehicleStore(File(app.filesDir, "paired-vehicles/$hash")).upsert(
                                device.address, device.name.orEmpty(), seriesCode, info.dfuFirmwareVersion,
                                org.openridesync.app.vehicles.modelName(resolved), resolved, resolvedYear, PairedVehicleStore.State.PAIRED)
                            // Commit and keep finish before cancellation can dispatch back to Main.
                            association.keepAssociation()
                        }
                    }
                }
                checkCurrent(attempt)
                mutableState.value = state.value.copy(stage = PairingStage.PAIRED,
                    vehicleName = org.openridesync.app.vehicles.modelName(resolved))
            } catch (error: Exception) { failed(attempt, error) }
            finally {
                association.close()
                if (choice === association) { choice = null; sender = null }
            }
        }
    }

    fun takeChooser(attempt: String): IntentSender? {
        if (!current(attempt) || state.value.stage != PairingStage.CHOOSING || !state.value.chooserReady) return null
        return sender.also {
            launchedChoice = attempt to checkNotNull(choice)
            sender = null; mutableState.value = state.value.copy(chooserReady = false)
        }
    }
    fun chooserResult(attempt: String?, resultCode: Int, data: Intent?) {
        val launched = launchedChoice
        if (attempt != null && launched?.first == attempt) {
            launched.second.onResult(resultCode, data)
            launchedChoice = null
        }
    }
    fun chooserLaunchFailed(attempt: String) {
        if (launchedChoice?.first == attempt) launchedChoice = null
        if (current(attempt)) { cancel(); mutableState.value = state.value.copy(stage = PairingStage.FAILED, problem = "CHOOSER_FAILED") }
    }
    fun selectModel(model: Model) {
        if (state.value.stage == PairingStage.MODEL_CHOICE && model in state.value.candidates) modelChoice?.complete(model)
    }
    fun cancel() {
        scanEpoch++
        pausedScan = false
        job?.cancel()
        releaseAfterJob()
        job = null
        // The running coroutine closes its association in finally. In particular, do not
        // disassociate underneath an atomic file commit that has already started.
        sender = null
        modelChoice?.cancel(); modelChoice = null
        mutableState.value = state.value.copy(stage = PairingStage.CANCELLED, chooserReady = false, candidates = emptyList())
    }
    fun clear() {
        cancel()
        uid = null; selected = null
        mutableState.value = PairingState()
    }
    private fun launchAttempt(attempt: String, action: suspend CoroutineScope.() -> Unit) {
        val ticket = checkNotNull(operationTicket)
        job = viewModelScope.launch(block = action).also { running ->
            running.invokeOnCompletion {
                // Keep ownership between scan and chooser, but release only after terminal cleanup.
                if (!current(attempt) || state.value.stage in setOf(PairingStage.PAIRED, PairingStage.FAILED, PairingStage.CANCELLED))
                    app.vehicleOperations.release(ticket)
            }
        }
    }
    private fun releaseAfterJob() {
        val ticket = operationTicket ?: return
        operationTicket = null
        val running = job
        if (running == null) app.vehicleOperations.release(ticket)
        else running.invokeOnCompletion { app.vehicleOperations.release(ticket) }
    }
    private suspend fun <T> recordStep(attempt: String, event: Event, action: suspend () -> T): T {
        val component = if (event == Event.BLE_BTU_INFORMATION) Component.BLE else Component.UI
        app.journal?.record(component, attempt, event, Outcome.STARTED, ErrorCode.NONE)
        try {
            return action().also { app.journal?.record(component, attempt, event, Outcome.SUCCEEDED, ErrorCode.NONE) }
        } catch (error: Exception) {
            val code = when (error) {
                is TimeoutCancellationException -> ErrorCode.TIMEOUT
                is CancellationException -> ErrorCode.CANCELLED
                is GattFailure -> error.code
                is SecurityException -> ErrorCode.PERMISSION_DENIED
                is java.io.IOException -> ErrorCode.IO
                is IllegalArgumentException -> ErrorCode.INVALID_ARGUMENT
                else -> ErrorCode.UNKNOWN
            }
            app.journal?.record(component, attempt, event,
                if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
            throw error
        }
    }
    private fun failed(attempt: String, error: Exception) {
        if (!current(attempt)) return
        val code = when (error) {
            is TimeoutCancellationException -> "TIMEOUT"
            is PairingBluetooth.Failure -> error.problem.name
            is GattFailure -> error.code.name
            is java.io.IOException -> if (state.value.stage == PairingStage.SAVING) "STORAGE_FAILED" else "NETWORK"
            else -> HondaSessionRead.errorCode(error)
        }
        mutableState.value = state.value.copy(stage = if (code == "CANCELLED") PairingStage.CANCELLED else PairingStage.FAILED,
            chooserReady = false, candidates = emptyList(), problem = code)
    }
    override fun onCleared() { clear(); super.onCleared() }
    private companion object { val writes = Mutex() }
}
