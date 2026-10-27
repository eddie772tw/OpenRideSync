package org.openridesync.app.pairing

import android.app.Activity
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.companion.*
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.Locale
import java.util.regex.Pattern

/** One user-approved system picker. Cancellation never removes an existing association/bond. */
@SuppressLint("MissingPermission")
class CompanionChoice(context: Context, private val journal: DiagnosticJournal?, private val operation: String) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)
    private val lock = Any()
    private val result = CompletableDeferred<Pair<Int, Intent?>>()
    private val created = CompletableDeferred<AssociationInfo>()
    private var started = false
    private var closed = false
    private var kept = false
    private var launched = false
    private var previousIds = emptySet<Int>()
    private var previousMacs = emptySet<String>()
    private var newId: Int? = null
    private var newMac: String? = null

    @Suppress("DEPRECATION")
    suspend fun await(onReady: (IntentSender) -> Unit): BluetoothDevice {
        journal?.record(Component.UI, operation, Event.PAIRING_ASSOCIATION, Outcome.STARTED, ErrorCode.NONE)
        try {
            return withTimeout(60_000) {
                currentCoroutineContext().ensureActive()
                val service = manager ?: throw failure(PairingBluetooth.Problem.UNSUPPORTED)
                synchronized(lock) {
                    if (started || closed) throw failure()
                    started = true
                    if (Build.VERSION.SDK_INT >= 33) previousIds = service.myAssociations.map { it.id }.toSet()
                    else previousMacs = service.associations.map { it.uppercase(java.util.Locale.ROOT) }.toSet()
                }
                val callback = object : CompanionDeviceManager.Callback() {
                    private fun ready(sender: IntentSender) {
                        synchronized(lock) {
                            if (closed || launched || result.isCompleted) return
                            launched = true
                        }
                        onReady(sender)
                    }
                    override fun onAssociationPending(intentSender: IntentSender) = ready(intentSender)
                    @Deprecated("Android 30–32 callback")
                    override fun onDeviceFound(chooserLauncher: IntentSender) = ready(chooserLauncher)
                    @androidx.annotation.RequiresApi(33)
                    override fun onAssociationCreated(associationInfo: AssociationInfo) {
                        synchronized(lock) {
                            if (associationInfo.id !in previousIds) newId = associationInfo.id
                            created.complete(associationInfo)
                        }
                        cleanupIfClosed()
                    }
                    override fun onFailure(error: CharSequence?) {
                        result.completeExceptionally(failure()) // Provider text is never copied to diagnostics/UI.
                        created.completeExceptionally(failure())
                    }
                }
                val filter = BluetoothLeDeviceFilter.Builder().setNamePattern(Pattern.compile("HONDA BTU [A-Za-z0-9]*")).build()
                val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
                currentCoroutineContext().ensureActive()
                if (Build.VERSION.SDK_INT >= 33) service.associate(request, context.mainExecutor, callback)
                else service.associate(request, callback, Handler(Looper.getMainLooper()))
                val (code, data) = result.await()
                if (code != Activity.RESULT_OK) throw failure(PairingBluetooth.Problem.CANCELLED)
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    val scanResultDevice = try {
                        data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)?.device
                    } catch (_: RuntimeException) {
                        null
                    }
                    scanResultDevice ?: run {
                        val info = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                            ?: created.await()
                        val rawMac = info.deviceMacAddress?.toString() ?: throw failure()
                        val mac = rawMac.uppercase(Locale.ROOT)
                        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(mac) ?: throw failure()
                    }
                } else {
                    val selected = data?.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)?.device ?: throw failure()
                    selected
                }
                currentCoroutineContext().ensureActive()
                journal?.record(Component.UI, operation, Event.PAIRING_ASSOCIATION, Outcome.SUCCEEDED, ErrorCode.NONE)
                device
            }
        } catch (error: Exception) {
            val problem = when (error) {
                is TimeoutCancellationException -> PairingBluetooth.Problem.TIMEOUT
                is CancellationException -> PairingBluetooth.Problem.CANCELLED
                is SecurityException -> PairingBluetooth.Problem.PERMISSION_REQUIRED
                is PairingBluetooth.Failure -> error.problem
                else -> PairingBluetooth.Problem.CHOOSER_FAILED
            }
            val errorCode = when (error) {
                is TimeoutCancellationException -> ErrorCode.TIMEOUT
                is CancellationException -> ErrorCode.CANCELLED
                is SecurityException -> ErrorCode.PERMISSION_DENIED
                is IllegalArgumentException -> ErrorCode.INVALID_ARGUMENT
                is PairingBluetooth.Failure -> PairingBluetooth.diagnosticCode(error.problem)
                else -> ErrorCode.UNAVAILABLE
            }
            journal?.record(Component.UI, operation, Event.PAIRING_ASSOCIATION,
                if (problem == PairingBluetooth.Problem.CANCELLED) Outcome.CANCELLED else Outcome.FAILED,
                errorCode)
            close()
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            throw failure(problem)
        }
    }

    /** The caller must also match the attempt ID captured when launching this picker. */
    fun onResult(resultCode: Int, data: Intent?) {
        synchronized(lock) {
            // Capture before completing: prompt coroutine cancellation may discard the awaited
            // result. On API30–32 this can be the only identifier for the new association.
            if (resultCode == Activity.RESULT_OK) {
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val info = data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                        if (info != null && info.id !in previousIds) newId = info.id
                    } else {
                        @Suppress("DEPRECATION")
                        val selected = data?.getParcelableExtra<ScanResult>(CompanionDeviceManager.EXTRA_DEVICE)?.device
                        val mac = selected?.address?.uppercase(Locale.ROOT)
                        if (mac != null && mac !in previousMacs) newMac = mac
                    }
                } catch (error: RuntimeException) {
                    if (!closed) result.completeExceptionally(error)
                }
            }
            if (!closed) result.complete(resultCode to data)
        }
        cleanupIfClosed()
    }

    fun keepAssociation() = synchronized(lock) {
        check(!closed && result.isCompleted) { "Inactive device selection" }
        kept = true
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            result.cancel()
            created.cancel()
        }
        cleanupIfClosed()
    }

    @Suppress("DEPRECATION")
    private fun cleanupIfClosed() {
        val target = synchronized(lock) {
            if (!closed || kept) return
            (newId to newMac).also { newId = null; newMac = null }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33 && target.first != null) manager?.disassociate(target.first!!)
            else if (Build.VERSION.SDK_INT < 33 && target.second != null) manager?.disassociate(target.second!!)
        } catch (_: RuntimeException) {
            journal?.record(Component.UI, operation, Event.STOP, Outcome.FAILED, ErrorCode.UNAVAILABLE)
        }
    }

    private fun failure(problem: PairingBluetooth.Problem = PairingBluetooth.Problem.CHOOSER_FAILED) = PairingBluetooth.Failure(problem)
}
