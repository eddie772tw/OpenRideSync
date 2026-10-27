package org.openridesync.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID

// ATT Insufficient Authorization is 0x08 on the wire. Android only exposed the
// BluetoothGatt constant in API 33; keep the same response on our API 30+ range.
// https://developer.android.com/reference/android/bluetooth/BluetoothGatt#GATT_INSUFFICIENT_AUTHORIZATION
private const val ATT_INSUFFICIENT_AUTHORIZATION = 0x08

/** One selected peer, one lifetime. Registration and notification ACKs are platform evidence only. */
@SuppressLint("MissingPermission") // Permission revocation is caught and closes this instance.
internal class CurrentTimeServer(
    context: Context,
    private val peer: BluetoothDevice,
    private val journal: DiagnosticJournal,
    private val operation: String,
) : AutoCloseable {
    private val context = context.applicationContext
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val added = CompletableDeferred<Unit>()
    private var server: BluetoothGattServer? = null
    private var closed = false
    private var started = false
    private var ready = false
    private var registered = false
    private var subscribed = false
    private var sending = false
    private var pendingReasons = 0
    private val current = BluetoothGattCharacteristic(CURRENT, 18, 1)
    private val local = BluetoothGattCharacteristic(LOCAL, 2, 1)
    private val ccc = BluetoothGattDescriptor(CCC, 17)
    private val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        current.addDescriptor(ccc)
        addCharacteristic(current)
        addCharacteristic(local)
    }
    private val notificationDeadline: Runnable = Runnable { synchronized(lock) {
        if (!closed && sending) { log(Event.BLE_CTS_NOTIFY, ErrorCode.TIMEOUT); close() }
    } }

    suspend fun start() {
        val owner = currentCoroutineContext()
        journal.record(Component.BLE, operation, Event.BLE_CTS_START, Outcome.STARTED, ErrorCode.NONE)
        try {
            synchronized(lock) {
                owner.ensureActive()
                if (closed || started) throw GattFailure(ErrorCode.INVALID_STATE)
                started = true
                val manager = context.getSystemService(BluetoothManager::class.java)
                    ?: throw GattFailure(ErrorCode.UNAVAILABLE)
                server = manager.openGattServer(context, callback) ?: throw GattFailure(ErrorCode.UNAVAILABLE)
                if (server?.addService(service) != true) throw GattFailure(ErrorCode.UNAVAILABLE)
            }
            withTimeout(10_000) { added.await() }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                owner.ensureActive()
                if (closed) throw GattFailure(ErrorCode.CANCELLED)
                val filter = IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
                registered = true
                ready = true
            }
            journal.record(Component.BLE, operation, Event.BLE_CTS_START, Outcome.SUCCEEDED, ErrorCode.NONE)
        } catch (failure: Exception) {
            close()
            val code = when (failure) {
                is TimeoutCancellationException -> ErrorCode.TIMEOUT
                is CancellationException -> ErrorCode.CANCELLED
                is SecurityException -> ErrorCode.PERMISSION_DENIED
                is GattFailure -> failure.code
                else -> ErrorCode.UNAVAILABLE
            }
            log(Event.BLE_CTS_START, code)
            if (failure is CancellationException) throw failure
            throw GattFailure(code)
        }
    }

    private fun selected(device: BluetoothDevice) = device == peer
    private fun log(event: Event, code: ErrorCode) = journal.record(Component.BLE, operation, event,
        if (code == ErrorCode.NONE) Outcome.SUCCEEDED else Outcome.FAILED, code)

    private fun response(device: BluetoothDevice, id: Int, status: Int, offset: Int = 0, bytes: ByteArray? = null) {
        if (closed) return
        try {
            if (server?.sendResponse(device, id, status, offset, bytes) != true) {
                log(Event.BLE_CTS_REQUEST, ErrorCode.UNAVAILABLE); close()
            } else log(Event.BLE_CTS_REQUEST, if (status == 0) ErrorCode.NONE else ErrorCode.INVALID_ARGUMENT)
        } catch (_: SecurityException) { log(Event.BLE_CTS_REQUEST, ErrorCode.PERMISSION_DENIED); close() }
        catch (_: RuntimeException) { log(Event.BLE_CTS_REQUEST, ErrorCode.UNAVAILABLE); close() }
    }

    private fun read(device: BluetoothDevice, id: Int, offset: Int, bytes: ByteArray?) {
        when {
            !selected(device) -> response(device, id, ATT_INSUFFICIENT_AUTHORIZATION)
            !ready -> response(device, id, BluetoothGatt.GATT_FAILURE)
            bytes == null -> response(device, id, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
            offset < 0 || offset > bytes.size -> response(device, id, BluetoothGatt.GATT_INVALID_OFFSET)
            else -> response(device, id, 0, offset, bytes.copyOfRange(offset, bytes.size))
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, value: BluetoothGattService) = synchronized(lock) {
            if (!closed && value.uuid == SERVICE) {
                if (status == 0) added.complete(Unit)
                else added.completeExceptionally(GattFailure(ErrorCode.UNAVAILABLE))
            }
            Unit
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = synchronized(lock) {
            if (!closed && selected(device) && (newState == BluetoothProfile.STATE_DISCONNECTED || status != 0)) close()
        }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, id: Int, offset: Int, field: BluetoothGattCharacteristic) = synchronized(lock) {
            if (!closed) read(device, id, offset, when (field) {
                current -> CurrentTimeData.currentTime(LocalDateTime.now(), CurrentTimeData.AdjustReason.UNKNOWN)
                local -> CurrentTimeData.localTimeInformation(ZonedDateTime.now())
                else -> null
            })
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, id: Int, offset: Int, descriptor: BluetoothGattDescriptor) = synchronized(lock) {
            if (!closed) read(device, id, offset, if (descriptor === ccc) byteArrayOf(if (subscribed) 1 else 0, 0) else null)
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, id: Int, descriptor: BluetoothGattDescriptor, prepared: Boolean, needed: Boolean, offset: Int, value: ByteArray) = synchronized(lock) {
            if (closed) return@synchronized
            val status = ctsCccStatus(selected(device), ready, descriptor === ccc, prepared, offset, value)
            if (status == 0) {
                subscribed = value[0].toInt() == 1
                if (!subscribed) pendingReasons = 0
            }
            if (needed) response(device, id, status)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, id: Int, field: BluetoothGattCharacteristic, prepared: Boolean, needed: Boolean, offset: Int, value: ByteArray) = synchronized(lock) {
            if (needed) response(device, id, if (selected(device)) BluetoothGatt.GATT_WRITE_NOT_PERMITTED else ATT_INSUFFICIENT_AUTHORIZATION)
        }
        override fun onExecuteWrite(device: BluetoothDevice, id: Int, execute: Boolean) = synchronized(lock) {
            response(device, id, if (selected(device)) BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED else ATT_INSUFFICIENT_AUTHORIZATION)
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) = synchronized(lock) {
            if (!closed && selected(device) && sending) {
                handler.removeCallbacks(notificationDeadline)
                sending = false
                log(Event.BLE_CTS_NOTIFY, if (status == 0) ErrorCode.NONE else ErrorCode.UNAVAILABLE)
                if (status != 0) close() else sendPending()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = synchronized(lock) {
            val reason = when (intent.action) {
                Intent.ACTION_TIME_CHANGED -> 1
                Intent.ACTION_TIMEZONE_CHANGED -> 4
                else -> return@synchronized
            }
            if (!closed && ready && subscribed) { pendingReasons = pendingReasons or reason; sendPending() }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendPending() {
        if (closed || !ready || !subscribed || sending || pendingReasons == 0) return
        val payload = CurrentTimeData.currentTime(LocalDateTime.now(), CurrentTimeData.AdjustReason.UNKNOWN)
        payload[9] = pendingReasons.toByte() // Preserve both reason bits when broadcasts coalesce.
        pendingReasons = 0
        sending = true
        handler.postDelayed(notificationDeadline, 10_000)
        try {
            val active = server ?: throw IllegalStateException()
            val accepted = if (Build.VERSION.SDK_INT >= 33)
                active.notifyCharacteristicChanged(peer, current, false, payload) == BluetoothStatusCodes.SUCCESS
            else { current.value = payload; active.notifyCharacteristicChanged(peer, current, false) }
            if (!accepted) { log(Event.BLE_CTS_NOTIFY, ErrorCode.UNAVAILABLE); close() }
        } catch (_: SecurityException) { log(Event.BLE_CTS_NOTIFY, ErrorCode.PERMISSION_DENIED); close() }
        catch (_: RuntimeException) { log(Event.BLE_CTS_NOTIFY, ErrorCode.UNAVAILABLE); close() }
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        ready = false
        subscribed = false
        sending = false
        pendingReasons = 0
        handler.removeCallbacks(notificationDeadline)
        added.completeExceptionally(GattFailure(ErrorCode.CANCELLED))
        if (registered) { registered = false; try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {} }
        val active = server
        server = null
        try { active?.clearServices() } catch (_: Exception) {}
        try { active?.close() } catch (_: Exception) {}
    }

    private companion object {
        fun uuid(value: String): UUID = UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
        val SERVICE = uuid("1805")
        val CURRENT = uuid("2a2b")
        val LOCAL = uuid("2a0f")
        val CCC = uuid("2902")
    }
}

/** Pure ATT acceptance boundary; rejected writes must not change subscription state. */
internal fun ctsCccStatus(selected: Boolean, ready: Boolean, known: Boolean, prepared: Boolean, offset: Int, value: ByteArray): Int = when {
    !selected -> ATT_INSUFFICIENT_AUTHORIZATION
    !ready -> BluetoothGatt.GATT_FAILURE
    !known || prepared -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
    value.size != 2 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
    !value.contentEquals(byteArrayOf(1, 0)) && !value.contentEquals(byteArrayOf(0, 0)) -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
    else -> BluetoothGatt.GATT_SUCCESS
}
