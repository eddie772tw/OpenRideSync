package org.openridesync.app.runtime

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.navigation.*
import org.openridesync.app.calls.*
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.media.*
import org.openridesync.app.messages.IncomingMessages
import kotlinx.coroutines.flow.MutableStateFlow
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.honda.PairedVehicleStore
import org.openridesync.honda.wire.HondaWire.Model
import org.openridesync.honda.wire.HondaWire.Meter
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.InvocationTargetException

/** Real service/connection stop and completion paths, before Firebase or GATT acquisition.
 * Reflection seeds only the reserved attempt which normally follows authenticated connect().
 * Resource ownership checks seed transport references without opening Bluetooth or media browsers.
 * These checks do not claim cleanup or recovery of an established Bluetooth transport. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RideServiceLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var app: OpenRideSyncApplication
    private lateinit var ride: RideConnection
    private lateinit var service: RideService
    private val authInstance = AuthSession::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private var previousAuth: Any? = null

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousAuth = authInstance.get(null)
        authInstance.set(null, null)
        app = OpenRideSyncApplication()
        ReflectionHelpers.setField(app, "mBase", RuntimeEnvironment.getApplication())
        // Even a build with private auth assets must never initialize Firebase in this fixture.
        val offline = object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = error("Auth assets unavailable in lifecycle fixture")
        }
        val authConstructor = AuthSession::class.java.getDeclaredConstructor(Context::class.java,
            org.openridesync.diagnostics.DiagnosticJournal::class.java).apply { isAccessible = true }
        val auth = authConstructor.newInstance(offline, null)
        assertNull(auth.currentUser())
        authInstance.set(null, auth)
        ride = RideConnection(app)
        ReflectionHelpers.setField(app, "ride", ride)
        service = Robolectric.buildService(RideService::class.java).create().get()
        ReflectionHelpers.setField(service, "mApplication", app)
    }

    @After fun cleanup() {
        service.onDestroy()
        ReflectionHelpers.getField<CoroutineScope>(ride, "scope").cancel()
        authInstance.set(null, previousAuth)
        Dispatchers.resetMain()
    }

    private fun reserve(id: String): VehicleOperationGate.Ticket {
        assertEquals(RideStage.IDLE, ride.state.value.stage)
        assertNull(ReflectionHelpers.getField<Any?>(ride, "attempt"))
        val ticket = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.RUNTIME))
        val type = Class.forName("org.openridesync.app.runtime.RideConnection\$Attempt")
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        assertEquals(listOf(String::class.java, String::class.java, java.lang.Long.TYPE,
            String::class.java, VehicleOperationGate.Ticket::class.java), constructor.parameterTypes.toList())
        val attempt = constructor.newInstance(id, "synthetic-user", 0L, "00:00:00:00:00:00", ticket)
        ReflectionHelpers.setField(ride, "attempt", attempt)
        assertTrue(ride.accepts(id))
        return ticket
    }

    private fun transport(current: Any): Any =
        Class.forName("org.openridesync.app.runtime.RideConnection\$Transport")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance().also {
                ReflectionHelpers.setField(current, "transport", it)
                ReflectionHelpers.setField(it, "rawConnected", true) // Synthetic owner fixture, no GATT success.
            }

    private fun releaseTransport(current: Any, link: Any) {
        ride.javaClass.getDeclaredMethod("releaseTransport", current.javaClass, link.javaClass)
            .apply { isAccessible = true }.invoke(ride, current, link)
    }

    @Test fun unauthenticatedConnectRejectsBeforeReservingOrStartingAService() {
        val store = PairedVehicleStore(temporary.newFolder())
        val model = Model.newBuilder().setModelLabel("synthetic-bike").setModelSeriesLabel("synthetic-series")
            .setMeter(Meter.newBuilder().setGeneration(1)).build()
        store.upsert("00:11:22:33:44:55", "Synthetic meter", "synthetic-series", "1", "Synthetic bike",
            model, 0, PairedVehicleStore.State.PAIRED)
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(*RideConnection.permissions())
        val device = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
            .getRemoteDevice("00:11:22:33:44:55")
        assertNull(shadowOf(application).nextStartedService)
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())

        ride.connect(store.read().single())

        assertEquals(RideStage.FAILED, ride.state.value.stage)
        assertEquals("AUTH_REQUIRED", ride.state.value.errorCode)
        assertNull(ReflectionHelpers.getField<Any?>(ride, "attempt"))
        assertNull(shadowOf(application).nextStartedService)
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
        val ticket = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        app.vehicleOperations.release(ticket)
    }

    @Test fun reservedAttachWithInvalidIdentityCompletesBeforeGarageOrGattAcquisition() = runBlocking {
        reserve("invalid-identity")
        val device = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
            .getRemoteDevice("00:00:00:00:00:00")
        val owner = ReflectionHelpers.getField<CoroutineScope>(service, "scope")
        var completions = 0
        val pending = checkNotNull(ride.attach("invalid-identity", owner, {}, { completions++ }))
        pending.start()
        withTimeout(5_000) { pending.join() }

        assertTrue(pending.isCompleted)
        assertEquals(1, completions)
        assertFalse(ride.accepts("invalid-identity"))
        assertEquals(RideStage.IDLE, ride.state.value.stage)
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
        val ticket = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        app.vehicleOperations.release(ticket)
    }

    @Test fun deviceAdmissionRejectsDisabledBluetoothAndMissingBondWithoutOpeningGatt() {
        // This isolates the real checkedDevice guard. Offline auth cannot reach it through attach.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*RideConnection.permissions())
        val adapter = checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)
        val device = adapter.getRemoteDevice("00:11:22:33:44:55")
        val checked = RideConnection::class.java.getDeclaredMethod("checkedDevice", String::class.java)
            .apply { isAccessible = true }
        fun rejectedCode(): String {
            val failure = assertThrows(InvocationTargetException::class.java) { checked.invoke(ride, device.address) }
            assertTrue(failure.cause is RideFailure)
            return (failure.cause as RideFailure).code
        }

        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        assertEquals("BLUETOOTH_DISABLED", rejectedCode())
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(device).setBondState(BluetoothDevice.BOND_NONE)
        assertEquals("BOND_REQUIRED", rejectedCode())
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        assertEquals(device, checked.invoke(ride, device.address)) // Positive control for the same guard.
        assertTrue(shadowOf(device).bluetoothGatts.isEmpty())
    }

    @Test fun staleNotificationStopCannotCancelANewerReservedAttempt() {
        reserve("new-attempt")
        val owner = ReflectionHelpers.getField<CoroutineScope>(service, "scope")
        var completions = 0
        val pending = checkNotNull(ride.attach("new-attempt", owner, {}, { completions++ }))
        ReflectionHelpers.setField(service, "job", pending)
        service.onStartCommand(Intent().setAction(RideService.STOP)
            .putExtra(RideService.ATTEMPT, "old-attempt"), 0, 2)
        assertTrue(ride.accepts("new-attempt"))
        assertFalse(pending.isCancelled)
        assertEquals(0, completions)
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        // Positive control: the current notification does reach the actual stop path.
        service.onStartCommand(Intent().setAction(RideService.STOP)
            .putExtra(RideService.ATTEMPT, "new-attempt"), 0, 3)
        assertTrue(pending.isCancelled)
        assertEquals(1, completions)
        assertFalse(ride.accepts("new-attempt"))
    }

    @Test fun destructionCancelsPendingRuntimeAndCompletionReleasesItsGate() {
        val oldTicket = reserve("pending-attempt")
        val owner = ReflectionHelpers.getField<CoroutineScope>(service, "scope")
        var completions = 0
        val pending = checkNotNull(ride.attach("pending-attempt", owner, {}, { completions++ }))
        assertFalse(pending.isActive) // Same LAZY job returned to onStartCommand before start().
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        service.onDestroy()
        assertTrue(pending.isCancelled)
        assertEquals(1, completions)
        assertFalse(ride.accepts("pending-attempt"))
        assertEquals(RideStage.IDLE, ride.state.value.stage)
        assertNull(ride.media.value)
        val next = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        app.vehicleOperations.release(oldTicket)
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.RUNTIME))
        app.vehicleOperations.release(next)
    }

    @Test fun explicitStopClearsRouteBeforeTheServiceCompletes() {
        reserve("route-attempt")
        val current = ReflectionHelpers.getField<Any>(ride, "attempt")
        val link = transport(current)
        val source = NativeNavigation(app)
        ReflectionHelpers.setField(link, "navigation", source)
        val observation = NavigationObservation(NavigationStatus.OBSERVED,
            MapsInstruction("Synthetic private road", "200 m", null, null), sourceId = "route")
        ReflectionHelpers.getField<MutableStateFlow<NavigationObservation>>(source, "mutableState").value = observation
        ReflectionHelpers.getField<MutableStateFlow<NavigationObservation>>(ride, "mutableNavigation").value = observation
        ride.disconnect()
        assertEquals(RideStage.STOPPING, ride.state.value.stage)
        assertTrue("reservation stays until service delivery", ride.accepts("route-attempt"))
        assertNull(ride.navigation.value.instruction)
        assertNull(source.state.value.instruction)
        assertNull(ReflectionHelpers.getField<NativeNavigation?>(link, "navigation"))
        assertEquals(NavigationCancelResult.UNAVAILABLE, source.cancel("route"))
    }

    @Test fun explicitStopClearsCallerBeforeTheServiceCompletesWithoutSendingAnAction() {
        reserve("call-attempt")
        val current = ReflectionHelpers.getField<Any>(ride, "attempt")
        val link = transport(current)
        val source = NativeCalls(app)
        ReflectionHelpers.setField(link, "callSource", source)
        val inbox = CallInboxState(CallAccess.READY, listOf(CallItem("call", "Synthetic app", "synthetic.app",
            "Synthetic private caller", CallKind.INCOMING, setOf(CallAction.ANSWER, CallAction.DECLINE), false)))
        ReflectionHelpers.getField<MutableStateFlow<CallInboxState>>(source, "mutableState").value = inbox
        ReflectionHelpers.getField<MutableStateFlow<CallInboxState>>(ride, "mutableCallInbox").value = inbox
        ride.disconnect()
        assertEquals(RideStage.STOPPING, ride.state.value.stage)
        assertTrue(ride.accepts("call-attempt"))
        assertTrue(ride.callInbox.value.calls.isEmpty())
        assertTrue(source.state.value.calls.isEmpty())
        assertNull(ReflectionHelpers.getField<NativeCalls?>(link, "callSource"))
        assertEquals(CallDispatchResult.UNAVAILABLE, source.dispatch("call", CallAction.ANSWER))
    }

    @Test fun staleTransportCleanupCannotChangeNewPresentationOrReleaseAttemptResources() {
        reserve("transport-owner")
        val current = ReflectionHelpers.getField<Any>(ride, "attempt")
        val old = transport(current)
        val oldAcquisition = Job()
        ReflectionHelpers.setField(old, "favoriteJob", oldAcquisition)
        val next = transport(current)
        val nextAcquisition = Job()
        ReflectionHelpers.setField(next, "favoriteJob", nextAcquisition)
        val retained = NativeMedia(app)
        ReflectionHelpers.setField(current, "media", retained)
        val same = ride.javaClass.getDeclaredMethod("sameTransport", current.javaClass, old.javaClass)
            .apply { isAccessible = true }
        assertEquals(false, same.invoke(ride, current, old))
        assertEquals(true, same.invoke(ride, current, next))
        val state = RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB, rssi = -40,
            favoriteStart = FavoriteStartStatus.STARTING, modeRequest = ModeRequestStage.WAITING_FOR_MODE)
        ReflectionHelpers.getField<MutableStateFlow<RideState>>(ride, "mutableState").value = state
        val page = RidePageState(RidePageStage.MENU)
        ReflectionHelpers.getField<MutableStateFlow<RidePageState>>(ride, "mutableMusicPage").value = page

        releaseTransport(current, old)
        releaseTransport(current, old) // Late duplicate cleanup remains harmless.

        assertTrue(oldAcquisition.isCancelled)
        assertTrue(nextAcquisition.isActive)
        assertEquals(state, ride.state.value)
        assertEquals(page, ride.musicPage.value)
        assertSame(next, ReflectionHelpers.getField<Any>(current, "transport"))
        assertSame(retained, ReflectionHelpers.getField<NativeMedia>(current, "media"))
        assertFalse(ReflectionHelpers.getField<Boolean>(retained, "closed"))
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        releaseTransport(current, next)
        assertEquals(false, same.invoke(ride, current, next))
        ride.promotionFailed("transport-owner", "UNAVAILABLE")
    }

    @Test fun transportReleaseKeepsRideStateUntilTerminalCompletionClosesItExactlyOnce() {
        val ticket = reserve("retained-owner")
        val current = ReflectionHelpers.getField<Any>(ride, "attempt")
        val link = transport(current)
        val retained = NativeMedia(app)
        val messages = IncomingMessages(emptySet()) { true }
        ReflectionHelpers.setField(current, "media", retained)
        ReflectionHelpers.setField(current, "incoming", messages)
        ReflectionHelpers.setField(current, "startup", RideStartupState.READY)
        val acquisition = Job()
        ReflectionHelpers.setField(link, "favoriteJob", acquisition)
        ReflectionHelpers.getField<MutableStateFlow<RideState>>(ride, "mutableState").value =
            RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_HU, rssi = -40,
                favoriteStart = FavoriteStartStatus.STARTING)
        releaseTransport(current, link)
        assertTrue(acquisition.isCancelled)
        assertNull(ReflectionHelpers.getField<Any?>(current, "transport"))
        assertSame(messages, ReflectionHelpers.getField<IncomingMessages>(current, "incoming"))
        assertEquals(RideStartupState.READY, ReflectionHelpers.getField<RideStartupState>(current, "startup"))
        assertFalse(ReflectionHelpers.getField<Boolean>(retained, "closed"))
        assertEquals(RideStage.STOPPING, ride.state.value.stage)
        assertNull(ride.state.value.mode); assertNull(ride.state.value.rssi)
        assertEquals(FavoriteStartStatus.INTERRUPTED, ride.state.value.favoriteStart)
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))

        ride.promotionFailed("retained-owner", "UNAVAILABLE")
        assertTrue(ReflectionHelpers.getField<Boolean>(retained, "closed"))
        assertFalse(ride.accepts("retained-owner"))
        val next = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        // Stale finalization and releasing the old ticket cannot free the new owner's gate.
        ride.javaClass.getDeclaredMethod("finish", current.javaClass, String::class.java)
            .apply { isAccessible = true }.invoke(ride, current, null)
        app.vehicleOperations.release(ticket)
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.RUNTIME))
        app.vehicleOperations.release(next)
    }

    @Test fun attachFailureCancelsRetainedObserverBeforeJoiningAttemptChildren() = runBlocking {
        reserve("observer-owner")
        val current = ReflectionHelpers.getField<Any>(ride, "attempt")
        val dispatcher = StandardTestDispatcher()
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        var completions = 0
        val pending = checkNotNull(ride.attach("observer-owner", owner, {}, { completions++ }))
        val retained = NativeMedia(app)
        // Attaching a child starts its LAZY parent; queue that start until ownership is seeded.
        val observation = Job(pending) // A live attempt child, without any browser or controller.
        ReflectionHelpers.setField(current, "media", retained)
        ReflectionHelpers.setField(current, "mediaJob", observation)
        pending.start() // Offline identity rejects before garage/GATT; outer finally still must run.
        dispatcher.scheduler.runCurrent()
        withTimeout(5_000) { pending.join() }
        assertTrue(observation.isCancelled)
        assertTrue(ReflectionHelpers.getField<Boolean>(retained, "closed"))
        assertEquals(1, completions)
        assertFalse(ride.accepts("observer-owner"))
        val next = checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        app.vehicleOperations.release(next)
        owner.cancel()
    }

    @Test fun explicitStopRevokesRecoveryBeforeAnyTeardownStateCanRestartIt() = runBlocking {
        reserve("recovery-owner")
        val current = ReflectionHelpers.getField<Any>(ride, "attempt")
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var probes = 0
        val recovery = RideRecovery(owner, { probes++; awaitCancellation() }, {})
        ReflectionHelpers.setField(current, "recovery", recovery)
        recovery.rawState(false)
        recovery.retrying()
        assertEquals(1, probes)
        ride.disconnect()
        assertTrue(ReflectionHelpers.getField<Boolean>(recovery, "closed"))
        recovery.rawState(false)
        recovery.rawState(true)
        assertEquals(1, probes)
        assertEquals(RideStage.STOPPING, ride.state.value.stage)
        assertNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING))
        ride.promotionFailed("recovery-owner", "CANCELLED")
        assertFalse(ride.accepts("recovery-owner"))
        app.vehicleOperations.release(checkNotNull(app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING)))
        owner.cancel()
    }

    @Test fun stopCompletionKeepsFailureEvidenceWhileReleasingTheAttempt() {
        val directory = temporary.newFolder()
        DiagnosticJournal(directory).use { journal ->
            ReflectionHelpers.setField(app, "journal", journal)
            for ((error, outcome, code) in listOf(
                Triple(null, "SUCCEEDED", "NONE"),
                Triple("UNAVAILABLE", "FAILED", "UNAVAILABLE"),
                Triple("TIMEOUT", "FAILED", "TIMEOUT"),
                Triple("CANCELLED", "CANCELLED", "CANCELLED"),
            )) {
                val id = journal.newOperation()
                reserve(id)
                ride.disconnect()
                val current = ReflectionHelpers.getField<Any>(ride, "attempt")
                // Seed the already-classified completion result; no synthetic GATT success.
                ride.javaClass.getDeclaredMethod("finish", current.javaClass, String::class.java)
                    .apply { isAccessible = true }.invoke(ride, current, error)
                assertTrue(journal.flush(5_000))
                val rows = directory.resolve(DiagnosticJournal.FILE_NAME).readLines()
                    .drop(1).map { it.split('\t') }.filter { it[6] == id }
                assertEquals(listOf("STARTED", outcome), rows.map { it[8] })
                assertTrue(rows.all { it[7] == "BLE_RUNTIME_STOP" })
                assertEquals(code, rows.last()[9])
                assertEquals(RideStage.IDLE, ride.state.value.stage)
                assertFalse(ride.accepts(id))
                app.vehicleOperations.release(checkNotNull(
                    app.vehicleOperations.tryAcquire(VehicleOperationGate.Kind.PAIRING)))
            }
        }
    }
}
