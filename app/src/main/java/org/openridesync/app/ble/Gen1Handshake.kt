package org.openridesync.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.security.GeneralSecurityException
import java.security.interfaces.RSAPublicKey
import java.util.UUID

/** Security stage only: caller must select/recognize Gen1 and own the connection lifecycle.
 * No scanning, bond reset, vehicle selection, READY state, or application control writes. */
internal object Gen1Handshake {
    private val serial = Mutex()
    private val service = UUID.fromString("592c1017-9c7b-4a35-aba6-f268592fb8fc")
    private val status = UUID.fromString("a65b7152-589e-4933-a426-8f729bfad439")
    private val control = UUID.fromString("4fa28b27-6938-48f8-980e-7c7c9acc6c1e")
    private val response = UUID.fromString("ebb3656f-03a5-44ce-a22c-70cfccf5e247")

    @SuppressLint("MissingPermission")
    suspend fun authenticate(context: Context, device: BluetoothDevice, journal: DiagnosticJournal,
        operation: String = journal.newOperation()): Int =
        serial.withLock {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    throw GattFailure(ErrorCode.PERMISSION_DENIED, boundary = GattFailure.Boundary.SECURITY)
                val mac = try { device.address } catch (_: SecurityException) {
                    throw GattFailure(ErrorCode.PERMISSION_DENIED, boundary = GattFailure.Boundary.SECURITY)
                }
                val store = BleKeyStore(context)
                var key: ByteArray? = null
                try {
                    withTimeout(30_000) {
                        // OEM always reads vehicle status first, even when a local key exists.
                        GattSession(context, device, journal, operation).use { connection ->
                            connection.connect()
                            connection.discoverServices()
                            val secured = stage(journal, operation, Event.BLE_SECURITY_STATUS) {
                                Gen1Security.securedStatus(connection.read(service, status))
                            }
                            key = if (secured) {
                                store.read(mac) ?: throw GattFailure(ErrorCode.FAILED_PRECONDITION)
                            } else stage(journal, operation, Event.BLE_SECURITY_RSA_EXCHANGE) {
                                // Detect existing corruption/lost wrapping keys before mutating the BTU.
                                store.prepareForWrite(mac)
                                connection.requestMtu()
                                val pair = Gen1Security.newExchangeKeyPair()
                                currentCoroutineContext().ensureActive()
                                connection.write(service, control, Gen1Security.exchangeRequest(pair.public as RSAPublicKey))
                                Gen1Security.exchangeResponse(pair.private, connection.read(service, response))
                            }
                        }
                        val activeKey = key ?: throw GattFailure(ErrorCode.INVALID_STATE)
                        // Separate GATT session, matching the OEM K / m0 subscription boundaries.
                        GattSession(context, device, journal, operation).use { connection ->
                            connection.connect()
                            connection.discoverServices()
                            stage(journal, operation, Event.BLE_SECURITY_AES_VERIFY) {
                                connection.write(service, control, Gen1Security.validationRequest(activeKey))
                                Gen1Security.validationResponse(activeKey, connection.read(service, response))
                            }
                        }.also {
                            // Persist only a confirmed key, as OEM SabService.Z2 does. Once confirmed,
                            // finish the small atomic local write even if the caller cancels now.
                            withContext(NonCancellable) { store.write(mac, activeKey) }
                        }
                    }
                } catch (failure: Exception) {
                    val transportFailure = failure.gattFailureOrNull()
                    val code = errorCode(failure)
                    journal.record(Component.BLE, operation, Event.STATE_CHANGE,
                        if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
                    // Upstream transport/owner cancellation remains authoritative. Only a live
                    // security operation may label its own failure as downstream of retry.
                    currentCoroutineContext().ensureActive()
                    if (failure is CancellationException && failure !is TimeoutCancellationException && transportFailure == null) throw failure
                    throw GattFailure(code, transportFailure?.origin ?: GattFailure.Origin.OTHER,
                        transportFailure?.status, transportFailure?.connectionState, GattFailure.Boundary.SECURITY)
                } finally { key?.fill(0) }
            }
        }

    private suspend fun <T> stage(journal: DiagnosticJournal, operation: String, event: Event, action: suspend () -> T): T {
        journal.record(Component.BLE, operation, event, Outcome.STARTED, ErrorCode.NONE)
        return try {
            action().also { journal.record(Component.BLE, operation, event, Outcome.SUCCEEDED, ErrorCode.NONE) }
        } catch (failure: Exception) {
            val code = errorCode(failure)
            journal.record(Component.BLE, operation, event,
                if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
            throw failure
        }
    }

    private suspend fun errorCode(failure: Exception): ErrorCode = failure.gattFailureOrNull()?.code ?: when (failure) {
        is TimeoutCancellationException -> ErrorCode.TIMEOUT
        is CancellationException -> ErrorCode.CANCELLED
        is GattFailure -> failure.code
        is SecurityException -> ErrorCode.PERMISSION_DENIED
        is GeneralSecurityException -> ErrorCode.DATA_LOSS
        is BleKeyStoreException -> when (failure.code) {
            BleKeyError.INVALID_INPUT -> ErrorCode.INVALID_ARGUMENT
            BleKeyError.CORRUPT -> ErrorCode.DATA_LOSS
            BleKeyError.KEY_UNAVAILABLE -> ErrorCode.FAILED_PRECONDITION
            BleKeyError.IO -> ErrorCode.IO
        }
        else -> ErrorCode.UNKNOWN
    }
}
