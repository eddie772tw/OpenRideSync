package org.openridesync.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

/** Observed initialization steps, never an estimate of completion or evidence of meter output. */
internal enum class RuntimePhase { PREPARING, CONNECTING, DISCOVERING, READING_INFORMATION, SUBSCRIBING, REGISTERING, AUTHENTICATING }

/** Structured runtime initialization only. No service, reconnect, UI or READY persistence. */
internal object Gen1RuntimeConnection {
    private val owner = Mutex()
    private val dataService = UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f")
    private val functionSource = UUID.fromString("6c060578-d1d9-460a-b86f-eb97f01b2227")
    private val drivingSource = UUID.fromString("bb1d5836-bf35-11ed-afa1-0242ac120002")
    private val controlPoint = UUID.fromString("d2e23443-f1ad-4293-a70b-a9fd163f7b00")

    internal class Connection internal constructor(
        val information: BtuInfo,
        val generation: Int,
        val monitor: Gen1InputMonitor,
        val rssi: StateFlow<Int?>,
        val controlNotifications: ReceiveChannel<ByteArray>,
        val securityIncrementId: Deferred<Int>,
        val operation: String,
        private val transport: GattSession,
    ) {
        /** Initialization observers may attach before authentication, but cannot send control. */
        suspend fun writeControl(bytes: ByteArray) {
            if (!securityIncrementId.isCompleted) throw GattFailure(ErrorCode.FAILED_PRECONDITION)
            securityIncrementId.await()
            transport.write(dataService, controlPoint, bytes, GattFailure.Boundary.CONTROL)
        }
    }

    /** The hook must launch (not await indefinitely) any control consumer. Input channels are
     * already owned by monitor. The post-security block alone may submit control commands. */
    suspend fun <T> run(
        context: Context,
        device: BluetoothDevice,
        generation: Int,
        journal: DiagnosticJournal,
        operation: String = journal.newOperation(),
        onSubscribed: suspend CoroutineScope.(Connection) -> Unit = {},
        onProgress: suspend (RuntimePhase) -> Unit = {},
        onRawState: (Boolean) -> Unit = {},
        block: suspend CoroutineScope.(Connection) -> T,
    ): T {
        if (generation !in 1..2) throw GattFailure(ErrorCode.UNSUPPORTED)
        if (!owner.tryLock()) throw GattFailure(ErrorCode.ALREADY_EXISTS)
        journal.record(Component.BLE, operation, Event.BLE_RUNTIME_INIT, Outcome.STARTED, ErrorCode.NONE)
        var initialized = false
        try {
            return coroutineScope {
                val transport = GattSession(context, device, journal, operation,
                    GattFailure.Boundary.INITIALIZATION, onRawState)
                val verified = CompletableDeferred<Int>()
                val rssi = MutableStateFlow<Int?>(null)
                var cts: CurrentTimeServer? = null
                try {
                    if (needsTimeServer(context, device)) {
                        cts = CurrentTimeServer(context, device, journal, operation)
                        try { cts.start() }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            // OEM CTS startup is nonfatal; CurrentTimeServer already records its error.
                            cts.close()
                        }
                    }
                    onProgress(RuntimePhase.CONNECTING)
                    try { withTimeout(5_000) { transport.connect(autoConnect = true) } }
                    catch (timeout: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        throw GattFailure(ErrorCode.TIMEOUT, GattFailure.Origin.CONNECT_DEADLINE)
                    }
                    transport.requestMtuBestEffort()
                    delay(50)
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        while (isActive) {
                            val sample = transport.readRssi() ?: break
                            rssi.value = sample
                            delay(2_000)
                        }
                    }
                    onProgress(RuntimePhase.DISCOVERING)
                    transport.discoverServices()
                    onProgress(RuntimePhase.READING_INFORMATION)
                    val information = BtuInformation.read(transport)
                    val colors = runtimeColors(information)
                    onProgress(RuntimePhase.SUBSCRIBING)
                    val assignments = transport.subscribe(Gen1AssignmentExchange.SERVICE, Gen1AssignmentExchange.CONTROL)
                    val monitor = Gen1InputMonitor(journal, operation)
                    val functions = transport.subscribe(dataService, functionSource)
                    launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeFunctions(functions) }
                    if (transport.hasCharacteristic(dataService, drivingSource)) {
                        val driving = transport.subscribe(dataService, drivingSource)
                        launch(start = CoroutineStart.UNDISPATCHED) { monitor.consumeDriving(driving) }
                    }
                    val control = transport.subscribe(dataService, controlPoint)
                    val connection = Connection(information, generation, monitor, rssi.asStateFlow(), control,
                        verified, operation, transport)
                    onSubscribed(connection)
                    onProgress(RuntimePhase.REGISTERING)
                    Gen1AssignmentExchange.register(transport, assignments, 1, colors, journal, operation)
                    onProgress(RuntimePhase.AUTHENTICATING)
                    val increment = Gen1Handshake.authenticate(context, device, journal, operation)
                    currentCoroutineContext().ensureActive()
                    verified.complete(increment)
                    journal.record(Component.BLE, operation, Event.BLE_RUNTIME_INIT, Outcome.SUCCEEDED, ErrorCode.NONE)
                    initialized = true
                    block(connection)
                } finally {
                    verified.cancel()
                    coroutineContext.cancelChildren()
                    transport.close()
                    cts?.close()
                }
            }
        } catch (failure: Exception) {
            val transportFailure = failure.gattFailureOrNull()
            val code = when (failure) {
                is TimeoutCancellationException -> ErrorCode.TIMEOUT
                is GattFailure -> failure.code
                is CancellationException -> transportFailure?.code ?: ErrorCode.CANCELLED
                is SecurityException -> ErrorCode.PERMISSION_DENIED
                is IllegalArgumentException -> ErrorCode.DATA_LOSS
                else -> ErrorCode.UNKNOWN
            }
            // Later riding/stop failures belong to the runtime owner, not initialization.
            if (!initialized) journal.record(Component.BLE, operation, Event.BLE_RUNTIME_INIT,
                if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
            currentCoroutineContext().ensureActive()
            if (failure is CancellationException && code == ErrorCode.CANCELLED) throw failure
            throw transportFailure ?: GattFailure(code)
        } finally { owner.unlock() }
    }

    @SuppressLint("MissingPermission")
    private fun needsTimeServer(context: Context, peer: BluetoothDevice): Boolean = try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.bondedDevices?.none { it == peer && it.name?.startsWith("HONDA BTU ARD") == true } ?: true
    } catch (_: SecurityException) { true } // Actual CTS/connect operations retain their permission checks.
}

/** New valid series codes inherit OEM's default policy; absent/unknown raw fields do not. */
internal fun runtimeColors(info: BtuInfo): Boolean {
    val type = info.vehicleType ?: throw GattFailure(ErrorCode.UNSUPPORTED)
    val series = info.seriesCode ?: throw GattFailure(ErrorCode.UNSUPPORTED)
    if (type !in 0..1 || !series.matches(Regex("(?:[0-9A-F]{2}|[0-9A-F]{4}|[0-9A-F]{8})")))
        throw GattFailure(ErrorCode.UNSUPPORTED)
    return Gen1Assignments.colorRegistrationNeeded(type, series)
}
