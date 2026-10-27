package org.openridesync.app.ble

import android.Manifest
import android.app.Application
import android.bluetooth.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothGatt

/** Real transport operations/callbacks against Android shadows, without a vehicle, auth or service. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class GattSessionLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var device: BluetoothDevice
    private lateinit var directory: File
    private lateinit var journal: DiagnosticJournal
    private val sessions = mutableListOf<GattSession>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        device = adapter.getRemoteDevice("00:11:22:33:44:55")
        directory = temporary.newFolder()
        journal = DiagnosticJournal(directory)
    }

    @After fun cleanup() {
        sessions.forEach { it.close() }
        journal.close()
    }

    private fun session(boundary: GattFailure.Boundary = GattFailure.Boundary.TRANSPORT,
        onRawState: (Boolean) -> Unit = {}) =
        GattSession(app, device, journal, journal.newOperation(), boundary, onRawState).also { sessions.add(it) }
    private fun callback(gatt: BluetoothGatt) = checkNotNull(shadowOf(gatt).gattCallback)

    private suspend fun CoroutineScope.connect(session: GattSession): BluetoothGatt {
        val connected = async(start = CoroutineStart.UNDISPATCHED) { session.connect() }
        val gatt = shadowOf(device).bluetoothGatts.last()
        callback(gatt).onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        connected.await()
        return gatt
    }

    private suspend fun prepareNotifications(session: GattSession, gatt: BluetoothGatt, writable: Boolean = false): BluetoothGattCharacteristic {
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val field = BluetoothGattCharacteristic(FIELD,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ or
                (if (writable) BluetoothGattCharacteristic.PROPERTY_WRITE else 0),
            BluetoothGattCharacteristic.PERMISSION_READ)
        field.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(field)
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).allowCharacteristicNotification(field)
        session.discoverServices()
        return field
    }

    private fun assertDisconnections(expected: Int) {
        assertTrue(journal.flush(5_000))
        val rows = directory.resolve(DiagnosticJournal.FILE_NAME).readLines(Charsets.UTF_8).drop(1)
        assertEquals(expected, rows.count { it.split('\t')[7] == "DISCONNECTED" })
    }

    @Test fun closingPendingConnectCompletesTheWaitAndIsIdempotent() = runTest {
        val session = session()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.connect() } }
        val gatt = shadowOf(device).bluetoothGatts.single()
        assertFalse(pending.isCompleted)
        assertFalse(shadowOf(gatt).isClosed)

        session.close()

        assertEquals(ErrorCode.CANCELLED, (pending.await().exceptionOrNull() as? GattFailure)?.code)
        assertTrue(shadowOf(gatt).isClosed)
        session.close()
        assertDisconnections(1)
    }

    @Test fun disconnectOrErrorFailsPendingReadAndDiscardsBufferedNotifications() = runTest {
        for ((index, event) in listOf(BluetoothGatt.GATT_SUCCESS to BluetoothProfile.STATE_DISCONNECTED,
            133 to BluetoothProfile.STATE_CONNECTED, 133 to BluetoothProfile.STATE_DISCONNECTED,
            BluetoothGatt.GATT_SUCCESS to BluetoothProfile.STATE_DISCONNECTING).withIndex()) {
            val session = session()
            val gatt = connect(session)
            val field = prepareNotifications(session, gatt)
            val notifications = session.subscribe(SERVICE, FIELD)
            callback(gatt).onCharacteristicChanged(gatt, field, byteArrayOf(1, 2))
            assertFalse(notifications.isEmpty)
            // A null characteristic value makes the shadow start a read without replying.
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.read(SERVICE, FIELD) } }
            assertFalse(pending.isCompleted)

            callback(gatt).onConnectionStateChange(gatt, event.first, event.second)

            val original = pending.await().exceptionOrNull() as GattFailure
            assertEquals(ErrorCode.UNAVAILABLE, original.code)
            assertEquals(if (event.second in listOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING))
                GattFailure.Origin.DISCONNECTED else GattFailure.Origin.CONNECTION_STATUS, original.origin)
            assertEquals(event.first, original.status)
            assertEquals(event.second, original.connectionState)
            val failure = runCatching { notifications.receive() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertSame(original, (failure as CancellationException).gattFailureCause())
            assertSame(original, runCatching { session.read(SERVICE, FIELD) }.exceptionOrNull())
            assertTrue(shadowOf(gatt).isClosed)
            callback(gatt).onConnectionStateChange(gatt, event.first, event.second)
            session.close()
            assertDisconnections(index + 1)
        }
    }

    /** Default shadow replies synchronously; hold probes/writes for explicit callback delivery. */
    @Implements(BluetoothGatt::class)
    class PendingProbeGatt : ShadowBluetoothGatt() {
        var acceptProbe = false
        var acceptWrite = false
        val writeTypes = mutableListOf<Int>()
        @Implementation override fun readRemoteRssi(): Boolean = acceptProbe
        @Implementation override fun requestMtu(mtu: Int): Boolean = acceptProbe
        @Implementation override fun writeCharacteristic(field: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int {
            writeTypes.add(writeType)
            return if (acceptWrite) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
        }
        @Implementation override fun writeCharacteristic(field: BluetoothGattCharacteristic): Boolean {
            writeTypes.add(field.writeType)
            return acceptWrite
        }
    }

    @Test
    @Config(sdk = [30, 35], shadows = [PendingProbeGatt::class])
    fun writesUseAdvertisedPropertiesAndStillWaitForTheAttCallback() = runTest {
        for ((properties, expectedType) in listOf(
            BluetoothGattCharacteristic.PROPERTY_WRITE to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE to BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY to null,
        )) {
            val session = session()
            val gatt = connect(session)
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val field = BluetoothGattCharacteristic(FIELD, properties, BluetoothGattCharacteristic.PERMISSION_WRITE)
            service.addCharacteristic(field)
            shadowOf(gatt).addDiscoverableService(service)
            session.discoverServices()
            val shadow = Shadow.extract<PendingProbeGatt>(gatt)
            shadow.acceptWrite = true
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { session.write(SERVICE, FIELD, byteArrayOf(1, 2)) }
            }
            if (expectedType == null) {
                assertEquals(ErrorCode.UNSUPPORTED, (pending.await().exceptionOrNull() as GattFailure).code)
                assertTrue(shadow.writeTypes.isEmpty())
                assertTrue(shadowOf(gatt).isClosed)
            } else {
                assertEquals(listOf(expectedType), shadow.writeTypes)
                assertFalse(pending.isCompleted)
                callback(gatt).onCharacteristicWrite(gatt, field, BluetoothGatt.GATT_SUCCESS)
                pending.await().getOrThrow()
                assertFalse(shadowOf(gatt).isClosed)
            }
        }
    }

    @Test
    @Config(shadows = [PendingProbeGatt::class])
    fun initializationAndControlStatusFailuresKeepTheirOwnBoundary() = runTest {
        for (control in listOf(false, true)) {
            val session = session(GattFailure.Boundary.INITIALIZATION)
            val gatt = connect(session)
            val field = if (control) prepareNotifications(session, gatt, writable = true) else null
            val shadow = Shadow.extract<PendingProbeGatt>(gatt)
            shadow.acceptProbe = true
            shadow.acceptWrite = true
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
                if (control) session.write(SERVICE, FIELD, byteArrayOf(1), GattFailure.Boundary.CONTROL)
                else session.requestMtu()
            } }
            assertFalse(pending.isCompleted)
            if (control) callback(gatt).onCharacteristicWrite(gatt, checkNotNull(field), 133)
            else callback(gatt).onMtuChanged(gatt, 247, 133)
            val failure = pending.await().exceptionOrNull() as GattFailure
            assertEquals(GattFailure.Origin.OPERATION_STATUS, failure.origin)
            assertEquals(if (control) GattFailure.Boundary.CONTROL else GattFailure.Boundary.INITIALIZATION,
                failure.boundary)
            assertEquals(133, failure.status)
            assertTrue(shadowOf(gatt).isClosed)
            assertSame(failure, runCatching { session.read(SERVICE, FIELD) }.exceptionOrNull())
        }
        assertDisconnections(2)
    }

    @Test
    @Config(shadows = [PendingProbeGatt::class])
    fun mainDisconnectFencesRawStateBeforeControlAndNotificationFanoutWithOneTransportFailure() = runTest {
        val raw = mutableListOf<Boolean>()
        var writing: Deferred<Result<Unit>>? = null
        val session = session(GattFailure.Boundary.INITIALIZATION) { connected ->
            raw.add(connected)
            if (!connected) writing?.let { assertFalse(it.isCompleted) }
        }
        assertTrue(raw.isEmpty())
        val gatt = connect(session)
        val field = prepareNotifications(session, gatt, writable = true)
        val notifications = session.subscribe(SERVICE, FIELD)
        callback(gatt).onCharacteristicChanged(gatt, field, byteArrayOf(1))
        Shadow.extract<PendingProbeGatt>(gatt).acceptWrite = true
        // Resume the waiter inline so a regression emitting raw false after fanout is observable.
        val write = async(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) { runCatching {
            session.write(SERVICE, FIELD, byteArrayOf(2), GattFailure.Boundary.CONTROL)
        } }
        writing = write
        assertFalse(write.isCompleted)

        callback(gatt).onConnectionStateChange(gatt, 8, BluetoothProfile.STATE_DISCONNECTED)

        val failure = write.await().exceptionOrNull() as GattFailure
        assertEquals(GattFailure.Origin.DISCONNECTED, failure.origin)
        assertEquals(GattFailure.Boundary.TRANSPORT, failure.boundary)
        assertEquals(8, failure.status)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, failure.connectionState)
        val queueFailure = runCatching { notifications.receive() }.exceptionOrNull() as CancellationException
        assertSame(failure, queueFailure.gattFailureCause())
        assertSame(failure, runCatching { session.write(SERVICE, FIELD, byteArrayOf(3), GattFailure.Boundary.CONTROL) }.exceptionOrNull())
        assertEquals(listOf(true, false), raw)
        callback(gatt).onCharacteristicWrite(gatt, field, BluetoothGatt.GATT_SUCCESS)
        callback(gatt).onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        session.close()
        assertEquals(listOf(true, false), raw)
        assertTrue(shadowOf(gatt).isClosed)
        assertDisconnections(1)
    }

    @Test fun runtimePublishesRawConnectedBeforeSetupAndTeardownOnlyOnceWithoutAnInitialFalse() = runTest {
        shadowOf(device).setName("HONDA BTU ARD TEST")
        val adapter = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
        shadowOf(adapter).setBondedDevices(setOf(device))
        val events = mutableListOf<String>()
        val runtime = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
            Gen1RuntimeConnection.run(app, device, 1, journal,
                onProgress = { events.add(it.name) }, onRawState = { events.add("raw:$it") }) {
                fail("A shadow without BTU information must never authenticate")
            }
        } }
        assertEquals(listOf("CONNECTING"), events)
        val gatt = shadowOf(device).bluetoothGatts.single()
        // Discovery succeeds, but this arbitrary service is not valid BTU information.
        shadowOf(gatt).addDiscoverableService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY))
        callback(gatt).onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertEquals(listOf("CONNECTING", "raw:true"), events)
        advanceTimeBy(50)
        runCurrent()
        assertTrue(runtime.await().isFailure)
        assertTrue(events.indexOf("raw:true") < events.indexOf("DISCOVERING"))
        assertTrue(events.contains("READING_INFORMATION"))
        assertEquals(listOf("raw:true", "raw:false"), events.filter { it.startsWith("raw:") })
        assertTrue(shadowOf(gatt).isClosed)
        callback(gatt).onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback(gatt).onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(listOf("raw:true", "raw:false"), events.filter { it.startsWith("raw:") })
        assertDisconnections(1)
    }

    @Test
    @Config(shadows = [PendingProbeGatt::class])
    fun bestEffortProbeFailuresKeepTheMtuAndJournalWithoutWeakeningStrictOperations() = runTest {
        for (mtu in listOf(true, false)) {
            val session = session()
            val gatt = connect(session)
            val probe = Shadow.extract<PendingProbeGatt>(gatt)
            if (mtu) assertFalse(session.requestMtuBestEffort()) else assertNull(session.readRssi())
            assertFalse(shadowOf(gatt).isClosed)
            probe.acceptProbe = true
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { if (mtu) session.requestMtuBestEffort() else session.readRssi() }
            }
            assertFalse(pending.isCompleted)
            if (mtu) callback(gatt).onMtuChanged(gatt, 247, 133)
            else callback(gatt).onReadRemoteRssi(gatt, -40, 133)
            assertEquals(if (mtu) false else null, pending.await().getOrThrow())
            assertFalse(shadowOf(gatt).isClosed)
            if (mtu) {
                // Failed negotiation must retain MTU23: the 21-byte payload exceeds its ATT limit.
                prepareNotifications(session, gatt, writable = true)
                val size = runCatching { session.write(SERVICE, FIELD, ByteArray(21)) }.exceptionOrNull() as GattFailure
                assertEquals(ErrorCode.OUT_OF_RANGE, size.code)
                assertEquals(GattFailure.Origin.OTHER, size.origin)
            } else {
                val strict = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.requestMtu() } }
                callback(gatt).onMtuChanged(gatt, 247, 133)
                val failure = strict.await().exceptionOrNull() as GattFailure
                assertEquals(ErrorCode.IO, failure.code)
                assertEquals(GattFailure.Origin.OPERATION_STATUS, failure.origin)
                assertEquals(133, failure.status)
                assertNull(failure.connectionState)
            }
            assertTrue(shadowOf(gatt).isClosed)
        }
        assertDisconnections(2)
        val rows = directory.resolve(DiagnosticJournal.FILE_NAME).readLines(Charsets.UTF_8).drop(1)
        assertEquals(3, rows.count { it.split('\t').let { fields ->
            fields[7] == "REQUEST" && fields[8] == "FAILED" && fields[9] == "IO"
        } })
    }

    @Test
    @Config(shadows = [PendingProbeGatt::class])
    fun requiredStartRejectionAndCallbackDeadlineKeepDifferentOrigins() = runTest {
        val rejected = session(GattFailure.Boundary.INITIALIZATION)
        val rejectedGatt = connect(rejected)
        val start = runCatching { rejected.requestMtu() }.exceptionOrNull() as GattFailure
        assertEquals(ErrorCode.UNAVAILABLE, start.code)
        assertEquals(GattFailure.Origin.OPERATION_REJECTED, start.origin)
        assertEquals(GattFailure.Boundary.INITIALIZATION, start.boundary)
        assertTrue(shadowOf(rejectedGatt).isClosed)

        val timedOut = session()
        val gatt = connect(timedOut)
        Shadow.extract<PendingProbeGatt>(gatt).acceptProbe = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { timedOut.readRssi() } }
        advanceTimeBy(15_000)
        runCurrent()
        val timeout = pending.await().exceptionOrNull() as GattFailure
        assertEquals(ErrorCode.TIMEOUT, timeout.code)
        assertEquals(GattFailure.Origin.OPERATION_TIMEOUT, timeout.origin)
        assertEquals(GattFailure.Boundary.PROBE, timeout.boundary)
        assertTrue(shadowOf(gatt).isClosed)
        assertDisconnections(2)
    }

    @Test fun assignmentWrapperKeepsTheOriginalTransportFailure() = runTest {
        val session = session()
        val gatt = connect(session)
        callback(gatt).onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        val original = runCatching { session.read(SERVICE, FIELD) }.exceptionOrNull() as GattFailure
        val replies = Channel<ByteArray>()
        try {
            val wrapped = runCatching {
                Gen1AssignmentExchange.register(session, replies, 1, false, journal, journal.newOperation())
            }.exceptionOrNull()
            assertSame(original, wrapped)
            assertEquals(GattFailure.Origin.DISCONNECTED, original.origin)
        } finally { replies.cancel() }
    }

    @Test fun runtimeKeepsConnectionDeadlineSeparateFromStatusFailureAndReleasesOwner() = runTest {
        shadowOf(device).setName("HONDA BTU ARD TEST")
        val adapter = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
        shadowOf(adapter).setBondedDevices(setOf(device)) // Select the existing OEM no-CTS branch.
        val raw = mutableListOf<Boolean>()
        suspend fun runtime() = runCatching {
            Gen1RuntimeConnection.run(app, device, 1, journal, onRawState = { raw.add(it) }) {
                fail("Initialization must not succeed")
            }
        }.exceptionOrNull() as GattFailure

        val deadline = async(start = CoroutineStart.UNDISPATCHED) { runtime() }
        assertTrue(raw.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(GattFailure.Origin.CONNECT_DEADLINE, deadline.await().origin)
        assertEquals(listOf(false), raw)
        assertTrue(shadowOf(shadowOf(device).bluetoothGatts.single()).isClosed)

        val status = async(start = CoroutineStart.UNDISPATCHED) { runtime() }
        val gatt = shadowOf(device).bluetoothGatts.last()
        callback(gatt).onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_CONNECTED)
        val failure = status.await()
        assertEquals(GattFailure.Origin.CONNECTION_STATUS, failure.origin)
        assertEquals(133, failure.status)
        assertEquals(GattFailure.Boundary.TRANSPORT, failure.boundary)
        assertEquals(listOf(false, false), raw)
        assertTrue(shadowOf(gatt).isClosed)
    }

    @Test fun securitySessionRetainsItsOriginWithoutConvertingOwnerCancellation() = runBlocking {
        val security = async { runCatching { Gen1Handshake.authenticate(app, device, journal) } }
        // Handshake uses Dispatchers.IO; inject the callback only once that GATT exists.
        // Repeated late callbacks are harmless if observed between construction and assignment.
        withTimeout(5_000) {
            while (!security.isCompleted) {
                shadowOf(device).bluetoothGatts.lastOrNull()?.let { gatt ->
                    callback(gatt).onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
                }
                delay(1)
            }
        }
        val failure = security.await().exceptionOrNull() as GattFailure
        assertEquals(ErrorCode.UNAVAILABLE, failure.code)
        assertEquals(GattFailure.Origin.DISCONNECTED, failure.origin)
        assertEquals(133, failure.status)
        assertEquals(GattFailure.Boundary.SECURITY, failure.boundary)

        val cancelled = async { Gen1Handshake.authenticate(app, device, journal) }
        withTimeout(5_000) { while (shadowOf(device).bluetoothGatts.size < 2) delay(1) }
        val ownerCancellation = CancellationException("owner stopped", GattFailure(ErrorCode.UNAVAILABLE,
            GattFailure.Origin.DISCONNECTED, 133, BluetoothProfile.STATE_DISCONNECTED))
        cancelled.cancel(ownerCancellation)
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        val stopped = runCatching { cancelled.await() }.exceptionOrNull()
        assertTrue(stopped is CancellationException)
        assertEquals("owner stopped", stopped?.message)
        assertTrue(shadowOf(device).bluetoothGatts.all { shadowOf(it).isClosed })
        assertFalse(File(app.noBackupFilesDir, "ble-gen1-keys").exists())

        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val denied = runCatching { Gen1Handshake.authenticate(app, device, journal) }.exceptionOrNull() as GattFailure
        assertEquals(ErrorCode.PERMISSION_DENIED, denied.code)
        assertEquals(GattFailure.Boundary.SECURITY, denied.boundary)
    }

    @Test fun lateAndOldSourceCallbacksCannotReopenOrCompleteAnotherSession() = runTest {
        val oldRaw = mutableListOf<Boolean>()
        val currentRaw = mutableListOf<Boolean>()
        val old = session(onRawState = { oldRaw.add(it) })
        val oldGatt = connect(old)
        old.close()
        val current = session(onRawState = { currentRaw.add(it) })
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { current.connect() }
        val currentGatt = shadowOf(device).bluetoothGatts.last()

        callback(oldGatt).onConnectionStateChange(oldGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback(currentGatt).onConnectionStateChange(oldGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback(oldGatt).onConnectionStateChange(currentGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        runCurrent()
        assertFalse(connecting.isCompleted)
        assertEquals(listOf(true, false), oldRaw)
        assertTrue(currentRaw.isEmpty())
        assertTrue(shadowOf(oldGatt).isClosed)
        assertEquals(ErrorCode.INVALID_STATE, (runCatching { old.requestMtu() }.exceptionOrNull() as? GattFailure)?.code)

        callback(currentGatt).onConnectionStateChange(currentGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        connecting.await()
        assertEquals(listOf(true), currentRaw)
        val field = prepareNotifications(current, currentGatt)
        val read = async(start = CoroutineStart.UNDISPATCHED) { current.read(SERVICE, FIELD) }
        callback(currentGatt).onCharacteristicRead(oldGatt, field, byteArrayOf(7), BluetoothGatt.GATT_SUCCESS)
        callback(oldGatt).onCharacteristicRead(currentGatt, field, byteArrayOf(8), BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertFalse(read.isCompleted)
        callback(currentGatt).onCharacteristicRead(currentGatt, field, byteArrayOf(9), BluetoothGatt.GATT_SUCCESS)
        assertArrayEquals(byteArrayOf(9), read.await())
        assertFalse(shadowOf(currentGatt).isClosed)
        current.close()
        assertDisconnections(2)
    }

    @Test fun revokedConnectPermissionFailsTheNextAttAndClosesSubscriptions() = runTest {
        val session = session()
        val gatt = connect(session)
        prepareNotifications(session, gatt)
        val notifications = session.subscribe(SERVICE, FIELD)

        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val failure = runCatching { session.readRssi() }.exceptionOrNull()

        assertEquals(ErrorCode.PERMISSION_DENIED, (failure as? GattFailure)?.code)
        assertTrue(shadowOf(gatt).isClosed)
        val closed = runCatching { notifications.receive() }.exceptionOrNull()
        assertTrue(closed is CancellationException)
        assertEquals(ErrorCode.PERMISSION_DENIED, (closed as CancellationException).gattFailureCause()?.code)
        session.close()
        assertDisconnections(1)
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString("8536e103-6771-4d4b-9702-287cb5e1340f")
        val FIELD: UUID = UUID.fromString("6c060578-d1d9-460a-b86f-eb97f01b2227")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
