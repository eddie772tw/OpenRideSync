package org.openridesync.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.ReceiveChannel
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID
import java.nio.ByteBuffer

/** Failure provenance stays in memory; the journal retains its bounded ErrorCode schema.
 * A numeric status alone cannot identify the OEM retry branch. */
class GattFailure(
    val code: ErrorCode,
    val origin: Origin = Origin.OTHER,
    val status: Int? = null,
    val connectionState: Int? = null,
    val boundary: Boundary = Boundary.TRANSPORT,
) : Exception(code.name) {
    enum class Origin { OTHER, DISCONNECTED, CONNECTION_STATUS, OPERATION_STATUS, OPERATION_REJECTED,
        OPERATION_TIMEOUT, CONNECT_DEADLINE }
    enum class Boundary { TRANSPORT, INITIALIZATION, CONTROL, PROBE, SECURITY }
}

/** One explicitly selected peripheral, one outstanding ATT operation, no reconnect or scanning.
 * Cancellation/timeout closes the connection so late callbacks cannot complete a later request. */
@SuppressLint("MissingPermission") // Checked at every operation; revocation is also caught.
internal class GattSession(
    context: Context,
    private val device: BluetoothDevice,
    private val journal: DiagnosticJournal,
    private val operation: String,
    private val operationBoundary: GattFailure.Boundary = GattFailure.Boundary.TRANSPORT,
    private val onRawState: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val context = context.applicationContext
    private val serial = Mutex()
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private var terminalFailure: GattFailure? = null
    private var connected = false
    private var servicesDiscovered = false
    private var mtu = 23
    private var pending: Pending? = null
    private val notifications = mutableMapOf<Target, Notification>()

    private enum class Kind { CONNECT, SERVICES, MTU, READ, WRITE, DESCRIPTOR_WRITE, RSSI }
    private class RejectedOperation(val code: ErrorCode) : Exception(code.name)
    private data class Target(val service: UUID, val characteristic: UUID)
    private class Notification(val queue: GattNotificationQueue, var field: BluetoothGattCharacteristic? = null)
    private class Pending(val kind: Kind, val target: Target?, val boundary: GattFailure.Boundary) {
        val reply = CompletableDeferred<ByteArray>()
        var descriptor: BluetoothGattDescriptor? = null
    }

    /** Notification subscription is usable only after its CCCD write is acknowledged.
     * One collector per target; cancelling its channel invalidates the whole session.
     * Subscription lifetime ends with this connection. No reconnect or implicit indication mode. */
    @Suppress("DEPRECATION")
    suspend fun subscribe(service: UUID, characteristic: UUID): ReceiveChannel<ByteArray> {
        val target = Target(service, characteristic)
        val queue = GattNotificationQueue { shutdown(ErrorCode.CANCELLED) }
        // Reserve before waiting for ATT serialization, avoiding concurrent duplicate setup.
        synchronized(lock) {
            if (closed) throw (terminalFailure ?: GattFailure(ErrorCode.INVALID_STATE))
            if (notifications.containsKey(target)) throw GattFailure(ErrorCode.ALREADY_EXISTS)
            notifications[target] = Notification(queue)
        }
        try {
            execute(Kind.DESCRIPTOR_WRITE, Event.BLE_CCCD_WRITE, target) {
                val field = characteristic(service, characteristic)
                notifications.getValue(target).field = field
                if (field.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0)
                    throw GattFailure(ErrorCode.UNSUPPORTED)
                val descriptor = field.getDescriptor(CCCD) ?: throw GattFailure(ErrorCode.UNSUPPORTED)
                // Queue was installed before enabling, preserving pre-ACK notifications.
                pending?.descriptor = descriptor
                if (!active().setCharacteristicNotification(field, true)) throw RejectedOperation(ErrorCode.UNAVAILABLE)
                if (Build.VERSION.SDK_INT >= 33)
                    active().writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()) == BluetoothStatusCodes.SUCCESS
                else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()
                    active().writeDescriptor(descriptor)
                }
            }
            synchronized(lock) { active() }
            currentCoroutineContext().ensureActive()
            return queue.values
        } catch (failure: Exception) {
            // Also covers cancellation while waiting for the serial ATT lock, before registration.
            queue.cancel(failure)
            throw failure
        }
    }

    suspend fun connect(autoConnect: Boolean = false) {
        execute(Kind.CONNECT, Event.BLE_GATT_CONNECT) {
            if (gatt != null) throw GattFailure(ErrorCode.INVALID_STATE)
            gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
    }

    suspend fun discoverServices() {
        execute(Kind.SERVICES, Event.BLE_DISCOVER_SERVICES) { active().discoverServices() }
        synchronized(lock) { active(); servicesDiscovered = true }
    }

    fun hasService(service: UUID): Boolean = synchronized(lock) {
        val source = active()
        if (!servicesDiscovered) throw GattFailure(ErrorCode.INVALID_STATE)
        source.getService(service) != null
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = synchronized(lock) {
        val source = active()
        if (!servicesDiscovered) throw GattFailure(ErrorCode.INVALID_STATE)
        source.getService(service)?.getCharacteristic(characteristic) != null
    }

    suspend fun requestMtu() {
        execute(Kind.MTU, Event.REQUEST) { active().requestMtu(247) }
    }

    /** A rejected start or completed failure callback can retain the current MTU.
     * Timeout/cancellation/disconnection still invalidates this session. */
    suspend fun requestMtuBestEffort(): Boolean =
        execute(Kind.MTU, Event.REQUEST, continueOnProbeFailure = true, boundary = GattFailure.Boundary.PROBE) { active().requestMtu(247) }.isNotEmpty()

    /** Null means a rejected start or failed callback: stop polling without disconnecting. */
    suspend fun readRssi(): Int? = execute(Kind.RSSI, Event.REQUEST, continueOnProbeFailure = true, boundary = GattFailure.Boundary.PROBE) {
        active().readRemoteRssi()
    }.takeIf { it.isNotEmpty() }?.let { ByteBuffer.wrap(it).int }

    suspend fun read(service: UUID, characteristic: UUID): ByteArray =
        execute(Kind.READ, Event.REQUEST, Target(service, characteristic)) {
            val field = characteristic(service, characteristic)
            if (field.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0)
                throw GattFailure(ErrorCode.UNSUPPORTED)
            active().readCharacteristic(field)
        }

    @Suppress("DEPRECATION")
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray,
        boundary: GattFailure.Boundary = operationBoundary) {
        // Copy before suspending; callers cannot alter an enqueued security command.
        val bytes = value.copyOf()
        try {
            execute(Kind.WRITE, Event.REQUEST, Target(service, characteristic), boundary = boundary) {
                val field = characteristic(service, characteristic)
                val writeType = when {
                    field.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    field.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else -> throw GattFailure(ErrorCode.UNSUPPORTED)
                }
                // Do not invent fragment framing or depend on undocumented long writes.
                if (bytes.isEmpty() || bytes.size > 512 || bytes.size > mtu - 3)
                    throw GattFailure(ErrorCode.OUT_OF_RANGE)
                if (Build.VERSION.SDK_INT >= 33) {
                    active().writeCharacteristic(field, bytes, writeType) == BluetoothStatusCodes.SUCCESS
                } else {
                    field.writeType = writeType
                    field.value = bytes
                    active().writeCharacteristic(field)
                }
            }
        } finally { bytes.fill(0) }
    }

    private fun active(): BluetoothGatt =
        gatt?.takeIf { connected && !closed } ?: throw (terminalFailure ?: GattFailure(ErrorCode.INVALID_STATE))

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        active().getService(service)?.getCharacteristic(characteristic) ?: throw GattFailure(ErrorCode.UNSUPPORTED)

    private suspend fun execute(
        kind: Kind,
        event: Event,
        target: Target? = null,
        continueOnProbeFailure: Boolean = false,
        boundary: GattFailure.Boundary = operationBoundary,
        start: () -> Boolean,
    ): ByteArray = serial.withLock {
        val request = Pending(kind, target, boundary)
        journal.record(Component.BLE, operation, event, Outcome.STARTED, ErrorCode.NONE)
        try {
            val result = withTimeout(15_000) {
                val owner = currentCoroutineContext()
                synchronized(lock) {
                    owner.ensureActive()
                    if (closed) throw (terminalFailure ?: GattFailure(ErrorCode.INVALID_STATE))
                    if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        throw GattFailure(ErrorCode.PERMISSION_DENIED)
                    pending = request
                    owner.ensureActive()
                    if (!start()) throw RejectedOperation(ErrorCode.UNAVAILABLE)
                }
                request.reply.await()
            }
            journal.record(Component.BLE, operation, event, Outcome.SUCCEEDED, ErrorCode.NONE)
            result
        } catch (failure: Exception) {
            val code = when (failure) {
                is TimeoutCancellationException -> ErrorCode.TIMEOUT
                is CancellationException -> ErrorCode.CANCELLED
                is SecurityException -> ErrorCode.PERMISSION_DENIED
                is GattFailure -> failure.code
                is RejectedOperation -> failure.code
                else -> ErrorCode.UNKNOWN
            }
            journal.record(Component.BLE, operation, event,
                if (code == ErrorCode.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, code)
            if (continueOnProbeFailure && currentCoroutineContext().isActive &&
                (failure is RejectedOperation || failure is GattFailure && failure.origin == GattFailure.Origin.OPERATION_STATUS))
                return@withLock byteArrayOf()
            val transportFailure = failure as? GattFailure ?: GattFailure(code, when (failure) {
                is TimeoutCancellationException -> GattFailure.Origin.OPERATION_TIMEOUT
                is RejectedOperation -> GattFailure.Origin.OPERATION_REJECTED
                else -> GattFailure.Origin.OTHER
            }, boundary = request.boundary)
            shutdown(transportFailure)
            if (failure is CancellationException) {
                // The 15s ATT timeout has already left its own timeout scope. An outer
                // connection deadline or owner cancellation is still cancelled here.
                currentCoroutineContext().ensureActive()
                if (failure !is TimeoutCancellationException) throw failure
            }
            throw transportFailure
        } finally {
            synchronized(lock) { if (pending === request) pending = null }
        }
    }

    private fun finish(source: BluetoothGatt, kind: Kind, status: Int, target: Target? = null, value: ByteArray = byteArrayOf(), descriptor: BluetoothGattDescriptor? = null) {
        synchronized(lock) {
            if (source !== gatt || closed) return
            val request = pending ?: return
            if (request.kind != kind || request.target != target) return
            if (kind == Kind.DESCRIPTOR_WRITE && request.descriptor !== descriptor) return
            if (status == BluetoothGatt.GATT_SUCCESS) request.reply.complete(value.copyOf())
            else request.reply.completeExceptionally(GattFailure(ErrorCode.IO,
                GattFailure.Origin.OPERATION_STATUS, status, boundary = request.boundary))
        }
    }

    private fun changed(source: BluetoothGatt, field: BluetoothGattCharacteristic, value: ByteArray) {
        synchronized(lock) {
            if (source !== gatt || closed) return
            val subscription = notifications[Target(field.service.uuid, field.uuid)] ?: return
            if (subscription.field !== field) return
            if (!subscription.queue.offer(value)) shutdown(ErrorCode.RESOURCE_EXHAUSTED)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(source: BluetoothGatt, status: Int, newState: Int) {
            synchronized(lock) {
                if (source !== gatt || closed) return
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    connected = true
                    onRawState(true) // Raw link recovery precedes initialization and security.
                    finish(source, Kind.CONNECT, status)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || newState == BluetoothProfile.STATE_DISCONNECTING) {
                    shutdown(GattFailure(ErrorCode.UNAVAILABLE, GattFailure.Origin.DISCONNECTED, status, newState))
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    shutdown(GattFailure(ErrorCode.UNAVAILABLE, GattFailure.Origin.CONNECTION_STATUS, status, newState))
                }
            }
        }

        override fun onServicesDiscovered(source: BluetoothGatt, status: Int) = finish(source, Kind.SERVICES, status)

        override fun onMtuChanged(source: BluetoothGatt, negotiatedMtu: Int, status: Int) {
            synchronized(lock) {
                if (source !== gatt || closed) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (negotiatedMtu !in 23..517) { shutdown(ErrorCode.DATA_LOSS); return }
                    mtu = negotiatedMtu
                }
                finish(source, Kind.MTU, status, value = byteArrayOf(1))
            }
        }

        override fun onReadRemoteRssi(source: BluetoothGatt, rssi: Int, status: Int) =
            finish(source, Kind.RSSI, status, value = ByteBuffer.allocate(4).putInt(rssi).array())

        override fun onCharacteristicRead(source: BluetoothGatt, field: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(source, Kind.READ, status, Target(field.service.uuid, field.uuid), value)

        @Deprecated("Only used by Android 30–32")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(source: BluetoothGatt, field: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33)
                finish(source, Kind.READ, status, Target(field.service.uuid, field.uuid), field.value ?: byteArrayOf())
        }

        override fun onCharacteristicWrite(source: BluetoothGatt, field: BluetoothGattCharacteristic, status: Int) =
            finish(source, Kind.WRITE, status, Target(field.service.uuid, field.uuid))

        override fun onDescriptorWrite(source: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val field = descriptor.characteristic
            finish(source, Kind.DESCRIPTOR_WRITE, status, Target(field.service.uuid, field.uuid), descriptor = descriptor)
        }

        override fun onCharacteristicChanged(source: BluetoothGatt, field: BluetoothGattCharacteristic, value: ByteArray) =
            changed(source, field, value)

        @Deprecated("Only used by Android 30–32")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(source: BluetoothGatt, field: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) changed(source, field, field.value ?: byteArrayOf())
        }
    }

    override fun close() = shutdown(synchronized(lock) {
        if (pending?.reply?.isCompleted == false) ErrorCode.CANCELLED else ErrorCode.NONE
    })

    private fun shutdown(reason: ErrorCode) = shutdown(GattFailure(reason))

    private fun shutdown(failure: GattFailure) {
        val reason = failure.code
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            if (reason != ErrorCode.NONE && reason != ErrorCode.CANCELLED) terminalFailure = failure
            // Fence business actions before terminal replies resume their waiters.
            if (gatt != null) onRawState(false)
            connected = false
            servicesDiscovered = false
            pending?.reply?.completeExceptionally(failure)
            val streams = notifications.toMap()
            notifications.clear()
            for ((_, subscription) in streams) {
                try {
                    subscription.field?.let {
                        gatt?.setCharacteristicNotification(it, false)
                    }
                } catch (_: RuntimeException) { /* disconnect still owns cleanup */ }
                subscription.queue.cancel(if (reason == ErrorCode.NONE) null else failure)
            }
            gatt.also { gatt = null }
        }
        try { previous?.disconnect() } catch (_: RuntimeException) { /* permission can be revoked */ }
        try { previous?.close() } catch (_: RuntimeException) { /* already closed or revoked */ }
        // Rx's DisconnectOperation also publishes DISCONNECTED on disposal (including a
        // connection deadline). Preserve the original failure; teardown is a state event.
        journal.record(Component.BLE, operation, Event.DISCONNECTED,
            when (reason) { ErrorCode.NONE -> Outcome.OBSERVED; ErrorCode.CANCELLED -> Outcome.CANCELLED; else -> Outcome.FAILED }, reason)
    }

    private companion object { val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
