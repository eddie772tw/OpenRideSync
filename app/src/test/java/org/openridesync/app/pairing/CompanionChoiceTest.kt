package org.openridesync.app.pairing

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.diagnostics.DiagnosticJournal
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AssociationInfoBuilder
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CompanionChoiceTest {
    private lateinit var root: File
    private lateinit var journal: DiagnosticJournal
    private lateinit var context: Context
    private lateinit var manager: CompanionDeviceManager
    private lateinit var choice: CompanionChoice

    @Before fun setup() {
        root = Files.createTempDirectory("companion-choice-test-").toFile()
        journal = DiagnosticJournal(File(root, "journal"), 128, 4096)
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(CompanionDeviceManager::class.java)
        choice = CompanionChoice(context, journal, "00000000-0000-0000-0000-000000000001")
    }

    @After fun cleanup() {
        choice.close()
        journal.close()
        root.deleteRecursively()
    }

    @Test fun cancelledResultRecordsCancelled() = runTest {
        val pending = async { runCatching { choice.await {} } }
        runCurrent()
        assertNotNull(shadowOf(manager).lastAssociationCallback)
        choice.onResult(Activity.RESULT_CANCELED, null)
        assertEquals(PairingBluetooth.Problem.CANCELLED,
            (pending.await().exceptionOrNull() as PairingBluetooth.Failure).problem)
        assertTrue(journal.flush(1000))
        val content = File(root, "journal/" + DiagnosticJournal.FILE_NAME).readText()
        assertTrue(content.contains("PAIRING_ASSOCIATION\tCANCELLED\tCANCELLED"))
    }

    @Test fun closeCancelsAnAlreadyStartedOperation() = runTest {
        val pending = async { runCatching { choice.await {} } }
        runCurrent()
        assertNotNull(shadowOf(manager).lastAssociationCallback)
        choice.close()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
    }

    @Test @Config(sdk = [33, 34])
    fun lowercaseAssociationAddressResolvesAndOnlyNewAssociationIsRemoved() = runTest {
        shadowOf(manager).addAssociation(association(1))
        val selected = association(2)
        val pending = async { choice.await {} }
        runCurrent()
        shadowOf(manager).addAssociation(selected)
        choice.onResult(Activity.RESULT_OK,
            Intent().putExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, selected))
        assertEquals("AA:BB:CC:DD:EE:FF", pending.await().address)
        choice.close()
        assertEquals(listOf(1), manager.myAssociations.map { it.id })
    }

    @Test fun associationCallbackCanSupplyMissingIntentExtraAndKeepSelection() = runTest {
        val pending = async { choice.await {} }
        runCurrent()
        choice.onResult(Activity.RESULT_OK, Intent())
        runCurrent()
        assertFalse(pending.isCompleted)
        val selected = association(2)
        shadowOf(manager).addAssociation(selected)
        shadowOf(manager).lastAssociationCallback.onAssociationCreated(selected)
        assertEquals("AA:BB:CC:DD:EE:FF", pending.await().address)
        choice.keepAssociation()
        choice.close()
        assertEquals(listOf(2), manager.myAssociations.map { it.id })
    }

    @Test @Config(sdk = [30, 33, 34])
    fun scanResultUsesTheSelectedDevice() = runTest {
        val device = context.getSystemService(BluetoothManager::class.java).adapter
            .getRemoteDevice("AA:BB:CC:DD:EE:FF")
        @Suppress("DEPRECATION")
        val scan = ScanResult(device, null, -60, 0)
        val pending = async { choice.await {} }
        runCurrent()
        choice.onResult(Activity.RESULT_OK,
            Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, scan))
        assertSame(device, pending.await())
    }

    @Test fun lateCreatedCallbackCleansUpOnlyItsNewAssociation() = runTest {
        shadowOf(manager).addAssociation(association(1))
        val pending = async { runCatching { choice.await {} } }
        runCurrent()
        val callback = shadowOf(manager).lastAssociationCallback
        choice.close()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        val selected = association(2)
        shadowOf(manager).addAssociation(selected)
        callback.onAssociationCreated(selected)
        assertEquals(listOf(1), manager.myAssociations.map { it.id })
    }

    private fun association(id: Int) = AssociationInfoBuilder.newBuilder()
        .setId(id).setPackageName(context.packageName)
        .setDeviceMacAddress("aa:bb:cc:dd:ee:ff").build()
}
