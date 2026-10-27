package org.openridesync.app.trips

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.location.Location
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.openridesync.app.auth.AuthState
import org.openridesync.app.BuildConfig
import org.openridesync.app.auth.AuthStatus
import org.openridesync.honda.wire.PipelineWire
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

/** Production coordinator + native SQLite, synthetic platform/network only. Never real location/RPC. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class TripRecordingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clock = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(clock)
    private val calls = CoroutineScope(SupervisorJob() + dispatcher)
    private val environment = FakeEnvironment()
    private lateinit var context: Context
    private lateinit var model: TripRecording
    private val connected = MutableStateFlow(true)
    private var foreground = true
    private var owns = true
    private val attempt = UUID.randomUUID().toString()
    private var recording: Job? = null

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val root = temporary.newFolder("no-backup")
        val prefix = UUID.randomUUID().toString()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
            override fun getSharedPreferences(name: String?, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
        }
        model = TripRecording(context, environment, io = dispatcher)
        clock.runCurrent()
    }
    @After fun cleanup() {
        model.close(); calls.cancel(); clock.runCurrent()
        TripUploadJobs.update(context, false)
        Dispatchers.resetMain()
    }
    private fun startRide() {
        recording = calls.launch {
            model.recordRide("synthetic-A", 0, attempt, "synthetic-model", "synthetic-raw", "Synthetic vehicle",
                connected, { connected.value }, { foreground }, { owns })
        }
        clock.runCurrent()
    }
    private fun sample(time: Long, latitude: Double = 25.0) = Location("synthetic").apply {
        this.time = time; this.latitude = latitude; longitude = 121.0
        accuracy = 5f; speed = 3f; speedAccuracyMetersPerSecond = 1f
    }
    private fun emit(value: Location) { assertTrue(environment.points.tryEmit(value)); clock.runCurrent() }
    private fun points(uid: String = "synthetic-A") = TripPointStore(context, uid).use { it.batch() }

    @Test fun consentAndRealConnectionGateCaptureAndReconnectPreservesTripWithNoGapDistance() {
        startRide()
        assertEquals(0, environment.starts)
        assertFalse(model.state.value.enabled); assertFalse(model.state.value.recording)
        assertTrue(model.setEnabled(true)); clock.runCurrent()
        assertEquals(1, environment.starts); assertTrue(model.state.value.recording)
        emit(sample(1000)); emit(sample(2000, 25.001))
        val first = points()
        assertEquals(2, first.size); assertEquals(0f, first[0].distance)
        assertTrue(first[1].distance > 0f)
        assertEquals(1000L, first[0].time); assertEquals(BuildConfig.VERSION_CODE, first[0].appVersionCode)
        assertFalse(first[0].isDebug)
        assertEquals("synthetic-model", first[0].vehicleSeriesCode)
        assertEquals(attempt, first[0].appSessionId)
        connected.value = false; clock.runCurrent()
        emit(sample(3000)); assertEquals(2, points().size)
        assertFalse(model.state.value.recording)
        connected.value = true; clock.runCurrent(); emit(sample(4000, 25.002))
        assertEquals(2, environment.starts)
        val resumed = points().last()
        assertEquals(first[0].tripUuid, resumed.tripUuid); assertEquals(0f, resumed.distance)
        model.setEnabled(false); clock.runCurrent(); emit(sample(5000))
        recording?.cancel(); clock.runCurrent()
        assertEquals(3, points().size); assertEquals(0, environment.finalCalls); assertTrue(environment.uploads.isEmpty())
        assertEquals(0, environment.active)
    }

    @Test fun permissionAndForegroundCapabilityAreIndependentFromConsentAndDoNotAffectTheRide() {
        environment.granted = false
        model.setEnabled(true); startRide()
        assertEquals("LOCATION_PERMISSION", model.state.value.errorCode)
        assertEquals(0, environment.starts); assertTrue(model.state.value.enabled)
        environment.granted = true; foreground = false
        clock.advanceTimeBy(1000); clock.runCurrent()
        assertEquals("FOREGROUND_LOCATION_REQUIRED", model.state.value.errorCode)
        assertEquals(0, environment.starts)
        foreground = true; clock.advanceTimeBy(1000); clock.runCurrent()
        emit(sample(1000)); assertEquals(1, points().size)
        environment.granted = false; clock.advanceTimeBy(1000); clock.runCurrent()
        assertEquals(0, environment.active); assertFalse(model.state.value.recording)
        environment.uploadFailure = true // Existing points may sync without a new location grant.
        emit(sample(2000)); recording?.cancel(); clock.runCurrent()
        assertEquals(1, points().size); assertEquals(0, environment.finalCalls)
        assertEquals(1, environment.uploads.single().size)
    }

    @Test fun finalFixAddsZeroDistanceBeforeAutomaticUploadAndNoSyntheticEndFlag() {
        model.setEnabled(true); startRide(); emit(sample(1000)); emit(sample(2000, 25.001))
        environment.final = sample(3000, 25.002)
        environment.uploadWait = CompletableDeferred()
        recording?.cancel(); clock.runCurrent()
        assertEquals(0, environment.active); assertEquals(1, environment.finalCalls)
        assertEquals(1, environment.uploads.size)
        val captured = environment.uploads.single()
        assertEquals(3, captured.size); assertEquals(0f, captured.last().distance)
        assertEquals(captured.first().tripUuid, captured.last().tripUuid)
        assertEquals(3, points().size); assertTrue(model.state.value.ambiguous)
        environment.uploadWait?.complete(Unit); clock.runCurrent()
        assertTrue(points().isEmpty()); assertFalse(model.state.value.ambiguous)
        assertFalse(model.state.value.uploading)
    }

    @Test fun failedFinalFixAndAmbiguousUploadRetainExactPointsAcrossReopenAndRequireExplicitRetry() {
        model.setEnabled(true); startRide(); emit(sample(1000))
        val original = points()
        environment.finalFailure = true; environment.uploadFailure = true
        recording?.cancel(); clock.runCurrent()
        assertEquals(original, points()); assertTrue(model.state.value.ambiguous)
        assertEquals(1, environment.uploads.size)
        model.close(); model = TripRecording(context, environment, io = dispatcher); clock.runCurrent()
        assertTrue(model.state.value.enabled); assertTrue(model.state.value.ambiguous)
        assertEquals(1, model.state.value.pending)
        model.upload(automatic = true); clock.runCurrent()
        assertEquals(1, environment.uploads.size)
        environment.uploadFailure = false
        model.upload(); clock.runCurrent()
        assertEquals(2, environment.uploads.size)
        assertEquals(original, environment.uploads.last())
        assertTrue(points().isEmpty()); assertFalse(model.state.value.ambiguous)
    }

    @Test fun accountReplacementDuringRpcCannotAcknowledgeOldQueueOrPublishItUnderNewOwner() {
        model.setEnabled(true); startRide(); emit(sample(1000))
        environment.uploadWait = CompletableDeferred()
        model.upload(); clock.runCurrent()
        val original = points()
        assertEquals(1, environment.uploads.size)
        environment.user = "synthetic-B"; environment.epoch = 1
        environment.auth.value = AuthState(AuthStatus.SIGNED_IN, revision = 1); clock.runCurrent()
        environment.uploadWait?.complete(Unit); clock.runCurrent()
        emit(sample(2000)); clock.advanceTimeBy(1000); clock.runCurrent()
        assertEquals(original, points()); assertTrue(points("synthetic-B").isEmpty())
        assertFalse(model.state.value.enabled); assertEquals(0, model.state.value.pending)
        assertFalse(model.state.value.uploading); assertFalse(model.state.value.ambiguous)
        assertEquals(0, environment.active); assertEquals(0, environment.finalCalls)
    }

    @Test fun foregroundLostDuringFinalFixDiscardsLateFixAndCancellationKeepsPendingBatch() {
        model.setEnabled(true); startRide(); emit(sample(1000))
        environment.finalWait = CompletableDeferred()
        environment.uploadWait = CompletableDeferred()
        environment.final = sample(2000)
        recording?.cancel(); clock.runCurrent()
        assertEquals(1, environment.finalCalls)
        foreground = false
        environment.finalWait?.complete(Unit); clock.runCurrent()
        assertEquals(1, points().size)
        model.cancelUpload(); clock.runCurrent()
        assertTrue(model.state.value.ambiguous); assertFalse(model.state.value.uploading)
        model.upload(automatic = true); clock.runCurrent()
        assertEquals(1, environment.uploads.size)
    }

    @Test fun modelCodeUsesOnlyUnambiguousExactMetadataPairs() {
        fun model(vararg codes: Pair<String, String>) = org.openridesync.honda.wire.HondaWire.Model.newBuilder()
            .addAllVehicleCodes(codes.map { (raw, code) -> org.openridesync.honda.wire.HondaWire.VehicleCode.newBuilder()
                .setSeriesCode(raw).setModelCode(code).build() }).build()
        assertEquals("K0ZA", tripSeriesModelCode(model("03F5" to "K0ZA"), "03F5"))
        assertEquals("", tripSeriesModelCode(model("03F5" to "K0ZA"), "03f5"))
        assertEquals("", tripSeriesModelCode(model("03F5" to "K0ZA", "03F5" to "other"), "03F5"))
        assertEquals("", tripSeriesModelCode(model("03F5" to "K0ZA", "03F5" to ""), "03F5"))
    }

    @Test fun malformedExistingMarkerBlocksAutomaticWorkUntilExplicitUserRetry() {
        model.setEnabled(true)
        val preferences = context.getSharedPreferences("trip-recording", Context.MODE_PRIVATE)
        val markerKey = preferences.all.keys.single { it.endsWith(":enabled") }.removeSuffix("enabled") + "in-flight"
        preferences.edit().putInt(markerKey, 42).commit()
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        model.close(); model = TripRecording(context, environment, io = dispatcher); clock.runCurrent()
        assertTrue(model.state.value.ambiguous)
        model.upload(automatic = true); clock.runCurrent()
        assertTrue(environment.uploads.isEmpty())
        assertEquals(42, preferences.all[markerKey])
        model.upload(); clock.runCurrent()
        assertEquals(listOf(point), environment.uploads.single())
        assertFalse(preferences.contains(markerKey)); assertTrue(points().isEmpty())
    }

    @Test fun cancellingSenderKeepsOwnershipUntilCleanupBeforeExplicitSuccessorCanDispatch() {
        model.setEnabled(true); startRide(); emit(sample(1000))
        environment.uploadWait = CompletableDeferred()
        environment.uploadCleanup = CompletableDeferred()
        model.upload(); clock.runCurrent()
        model.cancelUpload(); clock.runCurrent()
        model.upload(); clock.runCurrent()
        assertEquals(1, environment.uploads.size); assertTrue(model.state.value.uploading)
        environment.uploadCleanup?.complete(Unit); clock.runCurrent()
        assertFalse(model.state.value.uploading); assertTrue(model.state.value.ambiguous)
        environment.uploadWait = null; environment.uploadCleanup = null
        model.upload(); clock.runCurrent()
        assertEquals(2, environment.uploads.size); assertFalse(model.state.value.uploading)
        assertTrue(points().isEmpty())
        model.setEnabled(false); clock.runCurrent()
    }

    @Test fun failedMarkerRemovalRestoresMemoryAndEmptyQueueRecoveryNeedsExplicitActionWithoutRpc() {
        val actual = context.getSharedPreferences("trip-recording", Context.MODE_PRIVATE)
        var failRemoval = true
        val failed = object : SharedPreferences by actual {
            override fun edit(): SharedPreferences.Editor {
                val editor = actual.edit()
                var removal = false
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value); return this
                    }
                    override fun remove(key: String?): SharedPreferences.Editor {
                        editor.remove(key); removal = true; return this
                    }
                    override fun commit(): Boolean {
                        val result = editor.commit()
                        if (removal && failRemoval) { failRemoval = false; return false }
                        return result
                    }
                }
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) =
                if (name == "trip-recording") failed else super.getSharedPreferences(name, mode)
        }
        model.close(); model = TripRecording(wrapped, environment, io = dispatcher); clock.runCurrent()
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        model.refresh(); clock.runCurrent()
        model.upload(); clock.runCurrent()
        assertTrue(points().isEmpty()); assertTrue(model.state.value.ambiguous)
        assertTrue(actual.all.values.any { it == point.uuid })
        model.refresh(); clock.runCurrent()
        assertEquals(0, model.state.value.pending)
        assertEquals("STORAGE_FAILED", model.state.value.errorCode)
        model.upload(automatic = true); clock.runCurrent()
        assertEquals(1, environment.uploads.size)
        model.upload(); clock.runCurrent()
        assertEquals(1, environment.uploads.size)
        assertFalse(model.state.value.ambiguous)
        assertFalse(actual.all.values.any { it == point.uuid })
    }

    @Test fun finalFixTimeoutFinishesWithinFiveSecondsAndKeepsEarlierPoints() {
        model.setEnabled(true); startRide(); emit(sample(1000))
        environment.finalWait = CompletableDeferred()
        environment.uploadFailure = true
        recording?.cancel(); clock.runCurrent()
        assertEquals(1, environment.finalCalls); assertFalse(recording!!.isCompleted)
        clock.advanceTimeBy(4_999); clock.runCurrent()
        assertFalse(recording!!.isCompleted)
        clock.advanceTimeBy(1); clock.runCurrent()
        assertTrue(recording!!.isCompleted)
        assertEquals(1, points().size); assertTrue(model.state.value.ambiguous)
        assertEquals(0, environment.active)
    }

    @Test fun coordinatorRejectsMockAndNonfiniteSamplesAndRequiresExplicitRetry() {
        model.setEnabled(true); startRide()
        val mock = sample(1000).also { androidx.core.location.LocationCompat.setMock(it, true) }
        val invalidMeasurement = sample(2000).apply { speed = Float.NaN }
        val invalidCoordinate = sample(3000).apply { latitude = Double.NaN }
        for ((index, bad) in listOf(mock, invalidMeasurement, invalidCoordinate).withIndex()) {
            emit(bad)
            assertTrue(points().isEmpty()); assertFalse(model.state.value.recording)
            assertEquals("INVALID_LOCATION", model.state.value.errorCode)
            assertEquals(0, environment.active)
            clock.advanceTimeBy(1_000); clock.runCurrent()
            assertEquals(index + 1, environment.starts)
            model.retryRecording(); clock.runCurrent()
        }
        emit(sample(4000))
        assertEquals(1, points().size)
        model.setEnabled(false); clock.runCurrent()
    }

    @Test fun emptyRecoveryClickCannotUploadNewlyArrivedPointsBehindAStaleZeroCount() {
        model.setEnabled(true)
        val preferences = context.getSharedPreferences("trip-recording", Context.MODE_PRIVATE)
        val markerKey = preferences.all.keys.single { it.endsWith(":enabled") }.removeSuffix("enabled") + "in-flight"
        preferences.edit().putInt(markerKey, 42).commit()
        model.refresh(); clock.runCurrent()
        assertEquals(0, model.state.value.pending); assertTrue(model.state.value.ambiguous)
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        model.upload(emptyOnly = true); clock.runCurrent()
        assertTrue(environment.uploads.isEmpty()); assertEquals(listOf(point), points())
        assertEquals(1, model.state.value.pending); assertEquals("QUEUE_CHANGED", model.state.value.errorCode)
        assertEquals(42, preferences.all[markerKey])
        model.upload(); clock.runCurrent()
        assertEquals(listOf(point), environment.uploads.single()); assertTrue(points().isEmpty())
    }

    @Test fun rejectedSampleAndFinalTimeoutLeaveClosedDiagnosticErrorsWithoutPointOrAccountData() {
        val folder = temporary.newFolder("diagnostics")
        model.close()
        org.openridesync.diagnostics.DiagnosticJournal(folder).use { journal ->
            model = TripRecording(context, environment, journal, dispatcher); clock.runCurrent()
            model.setEnabled(true); startRide()
            emit(sample(1000).apply { speed = Float.NaN })
            assertEquals("INVALID_LOCATION", model.state.value.errorCode)
            model.retryRecording(); clock.runCurrent(); emit(sample(2000))
            val saved = points().single()
            environment.finalWait = CompletableDeferred(); environment.uploadFailure = true
            recording?.cancel(); clock.runCurrent(); clock.advanceTimeBy(5_000); clock.runCurrent()
            model.close()
            journal.close()
            val text = File(folder, org.openridesync.diagnostics.DiagnosticJournal.FILE_NAME).readText()
            assertTrue(text.contains("\tSTATE_CHANGE\tFAILED\tINVALID_ARGUMENT\t"))
            assertTrue(text.contains("\tRESPONSE\tFAILED\tTIMEOUT\t"))
            assertEquals(0L, journal.status().dropped)
            for (privateValue in listOf("synthetic-A", "Synthetic vehicle", saved.uuid, saved.tripUuid, attempt)) {
                assertFalse(text.contains(privateValue))
            }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test fun durableJobIsRegisteredBeforeCaptureAndRestoredPendingUploadNeedsNoLocationOrVehicle() {
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(TripUploadJobs.ID))
        model.setEnabled(true)
        val scheduled = requireNotNull(scheduler.getPendingJob(TripUploadJobs.ID))
        assertTrue(scheduled.isPersisted); assertTrue(scheduled.isPeriodic)
        assertEquals(TripUploadJobs.INTERVAL, scheduled.intervalMillis)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, scheduled.networkType)
        assertTrue(scheduled.extras.isEmpty)
        startRide(); emit(sample(1000))
        model.close(); recording?.cancel(); clock.runCurrent()
        assertEquals(1, points().size); assertTrue(environment.uploads.isEmpty())
        environment.granted = false; foreground = false; connected.value = false
        val silent = object : TripRecordingEnvironment by environment {
            override val auth = object : StateFlow<AuthState> by environment.auth {
                override suspend fun collect(collector: FlowCollector<AuthState>): Nothing = awaitCancellation()
            }
        }
        model = TripRecording(context, silent, io = dispatcher); clock.runCurrent()
        assertFalse(model.state.value.signedIn) // No constructor-collector emission to initialize ownership.
        val background = calls.launch { model.runBackground() }; clock.runCurrent()
        assertTrue(model.state.value.signedIn)
        assertSame(scheduled, scheduler.getPendingJob(TripUploadJobs.ID))
        assertTrue(background.isCompleted)
        assertTrue(points().isEmpty()); assertEquals(1, environment.uploads.size)
        assertEquals(1, environment.starts); assertEquals(0, environment.finalCalls)
    }

    @Test fun stoppedBackgroundOwnsItsSenderUntilCleanupAndAmbiguityBlocksAnotherSystemWindow() {
        model.setEnabled(true)
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        environment.uploadWait = CompletableDeferred(); environment.uploadCleanup = CompletableDeferred()
        val background = calls.launch { model.runBackground() }; clock.runCurrent()
        assertEquals(1, environment.uploads.size)
        background.cancel(); clock.runCurrent()
        model.upload(); clock.runCurrent()
        assertTrue(model.state.value.uploading); assertEquals(1, environment.uploads.size)
        val overlapping = calls.launch { model.runBackground() }; clock.runCurrent()
        assertTrue(overlapping.isCompleted); assertFalse(background.isCompleted)
        environment.uploadCleanup?.complete(Unit); clock.runCurrent()
        assertTrue(background.isCompleted); assertFalse(model.state.value.uploading)
        assertEquals(listOf(point), points()); assertTrue(model.state.value.ambiguous)
        model.close(); model = TripRecording(context, environment, io = dispatcher); clock.runCurrent()
        calls.launch { model.runBackground() }; clock.runCurrent()
        assertEquals(1, environment.uploads.size); assertTrue(model.state.value.ambiguous)
        environment.uploadWait = null; environment.uploadCleanup = null
        model.upload(); clock.runCurrent()
        assertEquals(2, environment.uploads.size); assertTrue(points().isEmpty())
    }

    @Test fun backgroundThatFindsAnExplicitSenderDoesNotOwnOrCancelItAndOptOutKeepsRows() {
        model.setEnabled(true)
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        environment.uploadWait = CompletableDeferred()
        model.upload(); clock.runCurrent()
        val background = calls.launch { model.runBackground() }; clock.runCurrent(); background.cancel(); clock.runCurrent()
        assertTrue(model.state.value.uploading); assertEquals(1, environment.uploads.size)
        model.setEnabled(false); clock.runCurrent()
        assertFalse(model.state.value.uploading); assertEquals(listOf(point), points())
        assertNull(context.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(TripUploadJobs.ID))
        calls.launch { model.runBackground() }; clock.runCurrent()
        assertEquals(1, environment.uploads.size)
        environment.uploadWait = null; model.upload(); clock.runCurrent()
        assertTrue(points().isEmpty()); assertEquals(2, environment.uploads.size)
    }

    @Test fun sameAccountRevisionReplacementFencesBackgroundAcknowledgement() {
        model.setEnabled(true)
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        environment.uploadWait = CompletableDeferred()
        val background = calls.launch { model.runBackground() }; clock.runCurrent()
        environment.epoch++
        environment.auth.value = AuthState(AuthStatus.SIGNED_IN, revision = environment.epoch); clock.runCurrent()
        environment.uploadWait?.complete(Unit); clock.runCurrent()
        assertTrue(background.isCompleted); assertEquals(listOf(point), points())
        assertTrue(model.state.value.ambiguous); assertFalse(model.state.value.uploading)
        calls.launch { model.runBackground() }; clock.runCurrent()
        assertEquals(1, environment.uploads.size)
    }

    @Test fun unavailableSchedulerIsVisibleWithoutDiscardingConsentOrPendingRows() {
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.JOB_SCHEDULER_SERVICE) null else super.getSystemService(name)
        }
        model.close(); model = TripRecording(wrapped, environment, io = dispatcher); clock.runCurrent()
        assertTrue(model.setEnabled(true)); clock.runCurrent()
        assertTrue(model.state.value.enabled); assertTrue(model.state.value.backgroundScheduleFailed)
        model.refresh(); clock.runCurrent()
        assertTrue(model.state.value.backgroundScheduleFailed); assertTrue(environment.uploads.isEmpty())
        assertFalse(File(context.noBackupFilesDir, "trip-points").exists())
    }

    @Test fun closingCoordinatorCancelsItsBackgroundSenderButPreservesTheDurableTriggerAndRows() {
        model.setEnabled(true)
        val point = tripPoint(sample(1000), null, "synthetic-A", UUID.randomUUID().toString(), attempt, "", "", "", true)
        TripPointStore(context, "synthetic-A").use { it.append(point) }
        environment.uploadWait = CompletableDeferred(); environment.uploadCleanup = CompletableDeferred()
        val background = calls.launch { model.runBackground() }; clock.runCurrent()
        model.close(); clock.runCurrent()
        assertFalse(background.isCompleted); assertEquals(listOf(point), points())
        assertNotNull(context.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(TripUploadJobs.ID))
        environment.uploadCleanup?.complete(Unit); clock.runCurrent()
        assertTrue(background.isCompleted); assertEquals(listOf(point), points())
        model = TripRecording(context, environment, io = dispatcher); clock.runCurrent()
        assertTrue(model.state.value.ambiguous)
    }

    private class FakeEnvironment : TripRecordingEnvironment {
        override val auth = MutableStateFlow(AuthState(AuthStatus.SIGNED_IN))
        var user = "synthetic-A"; var epoch = 0L
        override val uid get() = user
        override val revision get() = epoch
        var granted = true
        override val locationGranted get() = granted
        override val locationEnabled = true
        override val coarse = true
        val points = MutableSharedFlow<Location>(extraBufferCapacity = 16)
        var starts = 0; var active = 0; var finalCalls = 0
        var final: Location? = null; var finalFailure = false; var finalWait: CompletableDeferred<Unit>? = null
        var uploadFailure = false; var uploadWait: CompletableDeferred<Unit>? = null
        var uploadCleanup: CompletableDeferred<Unit>? = null
        val uploads = mutableListOf<List<PipelineWire.Location>>()
        override fun samples() = points.onStart { starts++; active++ }.onCompletion { active-- }
        override suspend fun finalLocation(): Location? {
            finalCalls++; finalWait?.await()
            if (finalFailure) throw IOException("Synthetic final fix failure")
            return final
        }
        override suspend fun upload(uid: String, revision: Long, points: List<PipelineWire.Location>, operation: String, check: () -> Unit) {
            check(); uploads += points.toList()
            try {
                uploadWait?.await()
                if (uploadFailure) throw IOException("Synthetic ambiguous network result")
                check()
            } finally { uploadCleanup?.let { withContext(NonCancellable) { it.await() } } }
        }
    }
}
