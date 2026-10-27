package org.openridesync.app.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

/** Foreground pairing operations only; never unbonds or chooses a scan result for the rider. */
@SuppressLint("MissingPermission")
class PairingBluetooth(context: Context, private val journal: DiagnosticJournal?) {
    private val context = context.applicationContext
    enum class Problem { PERMISSION_REQUIRED, BLUETOOTH_DISABLED, LOCATION_DISABLED, UNSUPPORTED, SCAN_FAILED, TIMEOUT, BOND_FAILED, CHOOSER_FAILED, CANCELLED }
    class Failure(val problem: Problem) : Exception(problem.name)

    private fun adapter(): BluetoothAdapter {
        if (permissions().any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED })
            throw Failure(Problem.PERMISSION_REQUIRED)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw Failure(Problem.UNSUPPORTED)
        if (!adapter.isEnabled) throw Failure(Problem.BLUETOOTH_DISABLED)
        return adapter
    }

    suspend fun awaitAdvertisement(operation: String) = trace(operation, Event.BLE_SCAN) {
        val adapter = adapter()
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true)
            throw Failure(Problem.LOCATION_DISABLED)
        val scanner = adapter.bluetoothLeScanner ?: throw Failure(Problem.UNSUPPORTED)
        val found = CompletableDeferred<Unit>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Check the advertised UUID as well as the platform's filter result.
                if (result.scanRecord?.serviceUuids?.contains(SERVICE) == true) found.complete(Unit)
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(Failure(Problem.SCAN_FAILED)) }
        }
        try {
            withTimeout(120_000) {
                currentCoroutineContext().ensureActive()
                scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(SERVICE).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                found.await()
            }
        } finally {
            found.cancel()
            try { scanner.stopScan(callback) } catch (_: RuntimeException) { /* radio/permission can change */ }
        }
    }

    suspend fun bond(device: BluetoothDevice, operation: String) = trace(operation, Event.BLE_BOND) {
        adapter()
        val result = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                @Suppress("DEPRECATION")
                val changed = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed != device) return
                try {
                    // Read actual platform state instead of trusting the broadcast's integer extra.
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> result.complete(Unit)
                        BluetoothDevice.BOND_NONE -> result.completeExceptionally(Failure(Problem.BOND_FAILED))
                    }
                } catch (_: SecurityException) { result.completeExceptionally(Failure(Problem.PERMISSION_REQUIRED)) }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // Bluetooth broadcasts may originate from the privileged Bluetooth process, not system UID.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        try {
            withTimeout(30_000) {
                currentCoroutineContext().ensureActive()
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> result.complete(Unit)
                    BluetoothDevice.BOND_BONDING -> Unit
                    else -> if (!device.createBond()) throw Failure(Problem.BOND_FAILED)
                }
                result.await()
            }
        } finally {
            result.cancel()
            context.unregisterReceiver(receiver)
            // Public Android APIs cannot cancel an in-flight bond. No hidden API or automatic unbond.
        }
    }

    private suspend fun trace(operation: String, event: Event, block: suspend () -> Unit) {
        journal?.record(Component.BLE, operation, event, Outcome.STARTED, ErrorCode.NONE)
        try {
            block()
            journal?.record(Component.BLE, operation, event, Outcome.SUCCEEDED, ErrorCode.NONE)
        } catch (error: Exception) {
            val problem = when (error) {
                is TimeoutCancellationException -> Problem.TIMEOUT
                is CancellationException -> Problem.CANCELLED
                is Failure -> error.problem
                is SecurityException -> Problem.PERMISSION_REQUIRED
                else -> Problem.UNSUPPORTED
            }
            journal?.record(Component.BLE, operation, event,
                if (problem == Problem.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, diagnosticCode(problem))
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            throw Failure(problem)
        }
    }

    companion object {
        private val SERVICE = ParcelUuid(UUID.fromString("4d87b1ea-528b-4798-b002-e2e1442a2e86"))
        fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        internal fun diagnosticCode(problem: Problem): ErrorCode = when (problem) {
            Problem.PERMISSION_REQUIRED -> ErrorCode.PERMISSION_DENIED
            Problem.TIMEOUT -> ErrorCode.TIMEOUT
            Problem.CANCELLED -> ErrorCode.CANCELLED
            Problem.UNSUPPORTED -> ErrorCode.UNSUPPORTED
            else -> ErrorCode.UNAVAILABLE
        }
    }
}
