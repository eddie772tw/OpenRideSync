package org.openridesync.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import org.openridesync.diagnostics.DiagnosticReader
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** Real local journal + ViewModel. No Application.onCreate, Firebase, radio or system picker. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DiagnosticsViewModelTest {
    private lateinit var root: File
    private lateinit var journal: DiagnosticJournal
    private lateinit var app: OpenRideSyncApplication
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = Files.createTempDirectory("diagnostics-view-model-").toFile()
        journal = DiagnosticJournal(File(root, "journal"), 128, 4096)
        app = OpenRideSyncApplication()
        ReflectionHelpers.setField(app, "mBase", RuntimeEnvironment.getApplication())
        ReflectionHelpers.setField(app, "journal", journal)
    }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        journal.close()
        Dispatchers.resetMain()
        root.deleteRecursively()
    }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = DiagnosticsViewModel(app, saved).also {
        stores.add(ViewModelStore().apply { put("diagnostics", it) })
    }
    private suspend fun DiagnosticsViewModel.idle() = withTimeout(10_000) { state.first { !it.busy } }

    @Test fun exhaustedCheckpointStillOffersResumeAndRestoredPickerExportsOriginalArchive() = runBlocking {
        repeat(30) { journal.record(Component.BLE, null, Event.REQUEST, Outcome.OBSERVED, ErrorCode.NONE) }
        // Exhaust the reserved checkpoints too, reproducing a snapshot that cannot flush.
        repeat(40) { journal.flush(5000) }
        assertTrue(journal.status().sizeLimited)
        assertFalse(journal.flush(5000))
        val saved = SavedStateHandle()
        val first = model(saved)
        first.refresh()
        assertTrue(first.idle().sizeLimited)
        first.archiveAndResume()
        val resumed = first.idle()
        assertFalse(resumed.message, resumed.sizeLimited)
        assertEquals(1, resumed.archives.size)
        val archive = resumed.archives.single()
        assertFalse(archive.complete)
        val old = journal.snapshotArchive(archive.id, File(root, "old.tsv")).readBytes()
        assertTrue(journal.record(Component.APP, null, Event.START, Outcome.OBSERVED, ErrorCode.NONE))
        assertTrue(DiagnosticReader.read(journal.snapshot(File(root, "current.tsv"), 5000)).complete)
        assertFalse(first.prepareExport("00000000-0000-0000-0000-000000000000"))
        assertTrue(first.prepareExport(archive.id))
        assertFalse(first.prepareExport())
        first.refresh() // Picker owns the immutable target; no refresh/rotation may replace it.
        assertTrue(first.state.value.exportPending)
        val restored = model(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        assertTrue(restored.state.value.exportPending)
        val output = File(root, "archive.zip")
        restored.export(Uri.fromFile(output))
        assertTrue(restored.idle().message, output.isFile)
        ZipFile(output).use { zip ->
            assertArrayEquals(old, zip.getInputStream(zip.getEntry(DiagnosticJournal.FILE_NAME)).readBytes())
            assertTrue(zip.getInputStream(zip.getEntry("manifest.properties")).bufferedReader().readText().contains("complete=false"))
        }
        assertFalse(restored.state.value.exportPending)
        val unrequested = File(root, "unrequested.zip")
        restored.export(Uri.fromFile(unrequested))
        assertFalse(unrequested.exists())
    }

    @Test fun cancellingOrFailingPickerDoesNotArchiveOrLeaveActionsBlocked() {
        val model = model()
        assertTrue(model.prepareExport())
        model.export(null)
        assertFalse(model.state.value.exportPending)
        assertEquals("已取消匯出", model.state.value.message)
        assertTrue(model.prepareExport())
        model.exportLaunchFailed()
        assertFalse(model.state.value.exportPending)
        assertTrue(journal.archives().isEmpty())
    }
}
