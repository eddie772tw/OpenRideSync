package org.openridesync.app.trips

import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentName
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.honda.wire.PipelineWire
import java.io.File
import java.util.UUID

/** Own synthetic SQLite points and pure UI only. No recorder, GPS, Honda client, grant or vehicle. */
@RunWith(AndroidJUnit4::class)
class TripRecordingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeSchedulerAcceptsPersistedProtectedJobWhileProductionConsentStaysOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val app = target.applicationContext as org.openridesync.app.OpenRideSyncApplication
        instrumentation.runOnMainSync { assertFalse(app.tripRecording.state.value.enabled) }
        val scheduler = target.getSystemService(JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(TripUploadJobs.ID))
        val targetService = ComponentName(target, TripUploadJobService::class.java)
        @Suppress("DEPRECATION")
        val service = target.packageManager.getServiceInfo(targetService, 0)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
        try {
            assertTrue(TripUploadJobs.update(target, true))
            val job = requireNotNull(scheduler.getPendingJob(TripUploadJobs.ID))
            assertEquals(targetService, job.service)
            assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
            assertTrue(job.isPeriodic); assertTrue(job.isPersisted)
            assertEquals(TripUploadJobs.INTERVAL, job.intervalMillis)
            assertTrue(job.extras.isEmpty)
            instrumentation.runOnMainSync { assertFalse(app.tripRecording.state.value.enabled) }
        } finally { assertTrue(TripUploadJobs.update(target, false)) }
        assertNull(scheduler.getPendingJob(TripUploadJobs.ID))
    }

    @Test fun nativeCapturedBatchPreservesNewPointAndConsentUiRequiresExplicitCallbacks() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(target.cacheDir, "synthetic-trip-${UUID.randomUUID()}")
        check(root.mkdir())
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = root
        }
        val uid = "synthetic-instrumentation-account"
        val trip = UUID.randomUUID().toString()
        val session = UUID.randomUUID().toString()
        fun point(time: Long) = PipelineWire.Location.newBuilder().setUuid(UUID.randomUUID().toString())
            .setUserId(uid).setTripUuid(trip).setAppSessionId(session).setTime(time).setDeviceTime(time)
            .setLatitude(25f).setLongitude(121f).setProvider("synthetic").setPlatform("android").build()
        val state = mutableStateOf(TripRecordingState(signedIn = true))
        var uploads = 0
        val enables = mutableListOf<Boolean>()
        try {
            val first = point(1000)
            TripPointStore(context, uid).use { store ->
                store.append(first)
                val captured = store.batch()
                val later = point(2000)
                store.append(later)
                store.acknowledge(captured)
                assertEquals(listOf(later), store.batch())
            }
            TripPointStore(context, uid).use { assertEquals(1, it.count()) }
            TripPointStore(context, "another-synthetic-account").use { assertEquals(0, it.count()) }
            compose.setContent { MaterialTheme {
                TripRecordingContent(state.value, {}, {}, {}, {
                    enables += it; state.value = state.value.copy(enabled = it)
                }, { uploads++ }, {}, {})
            } }
            compose.onNodeWithTag("trip_recording_enabled").performScrollTo().assertIsOff()
            compose.onNodeWithTag("trip_recording_enabled").performClick()
            compose.onNodeWithTag("trip_recording_status").performScrollTo().assertTextContains("尚未開始紀錄", substring = true)
            compose.runOnIdle {
                assertEquals(listOf(true), enables); assertEquals(0, uploads)
                state.value = state.value.copy(enabled = false, pending = 0, ambiguous = true)
            }
            compose.onNodeWithTag("trip_recording_upload").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("檢查佇列並清除未確認標記").assertExists()
            compose.onNodeWithTag("trip_recording_upload").performClick()
            compose.runOnIdle { assertEquals(1, uploads) }
            InstrumentationRegistry.getArguments().getString("uiGalleryOutputDir")?.takeIf { it.isNotBlank() }?.let { path ->
                val output = File(path).also { check(it.isDirectory || it.mkdirs()) }
                compose.onNodeWithTag("trip_recording_screen").captureToImage().asAndroidBitmap().let { bitmap ->
                    File(output, "synthetic-trip-recording-empty-recovery.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            }
        } finally {
            check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile && root.name.startsWith("synthetic-trip-"))
            check(root.deleteRecursively())
        }
    }
}
