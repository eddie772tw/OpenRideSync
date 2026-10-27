package org.openridesync.app.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*

internal enum class RideStatusKind { BATTERY, MOBILE, WIFI }
internal enum class RideStatusReportStage { IDLE, LOADING, SPEAKING, COMPLETE, FAILED }
internal data class RideStatusReportState(val kind: RideStatusKind? = null,
    val stage: RideStatusReportStage = RideStatusReportStage.IDLE,
    val detail: String? = null, val error: String? = null)

/** Explicit, single-report owner. Names live only in this page's memory and native speech. */
internal class RideStatusReport(private val context: Context, private val journal: DiagnosticJournal?,
    private val operation: String, private val isCurrent: () -> Boolean,
    private val sample: suspend () -> RideDeviceStatus = { withContext(Dispatchers.IO) { readRideDeviceStatus(context) } },
    private val speak: suspend (List<String>, () -> Boolean) -> Unit = { lines, valid ->
        speakRideLines(context, lines, journal, operation, valid)
    },
    private val fineLocationGranted: () -> Boolean = {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }) : AutoCloseable {
    private val mutableState = MutableStateFlow(RideStatusReportState())
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    private var revision = 0L
    private var closed = false

    suspend fun report(kind: RideStatusKind) = withContext(Dispatchers.Main.immediate) {
        if (closed || task != null) return@withContext
        val owner = currentCoroutineContext().job
        val attempt = ++revision
        task = owner
        fun valid() = !closed && revision == attempt && isCurrent()
        var readsWifiName = false
        fun permitted() = valid() && (!readsWifiName || fineLocationGranted())
        try {
            if (!valid()) throw CancellationException()
            mutableState.value = RideStatusReportState(kind, RideStatusReportStage.LOADING)
            val observed = sample()
            currentCoroutineContext().ensureActive()
            if (!valid()) throw CancellationException()
            readsWifiName = kind == RideStatusKind.WIFI && observed.wifi.availability == SignalAvailability.READY &&
                statusReportName(observed.wifi.name) != null
            if (!permitted()) throw CancellationException()
            val text = rideStatusReportText(kind, observed, context.resources.configuration.locales[0].language == "zh")
            mutableState.value = RideStatusReportState(kind, RideStatusReportStage.SPEAKING, text)
            speak(listOf(text), ::permitted)
            currentCoroutineContext().ensureActive()
            if (!permitted()) throw CancellationException()
            mutableState.value = RideStatusReportState(kind, RideStatusReportStage.COMPLETE, text)
        } catch (cancelled: CancellationException) {
            if (revision == attempt) mutableState.value = RideStatusReportState()
            throw cancelled
        } catch (error: Exception) {
            if (revision == attempt) {
                if (permitted()) {
                    val code = (error as? RideSpeechFailure)?.code ?: RideSpeechError.UNAVAILABLE
                    mutableState.value = RideStatusReportState(kind, RideStatusReportStage.FAILED, error = code.name)
                    if (error !is RideSpeechFailure)
                        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.UNAVAILABLE)
                } else mutableState.value = RideStatusReportState()
            }
        } finally {
            if (task === owner) task = null
        }
    }

    /** Main-thread owner, matching report and the ride page lifecycle. */
    fun cancel() {
        revision++
        mutableState.value = RideStatusReportState()
        val previous = task
        task = null
        previous?.cancel()
    }

    override fun close() { closed = true; cancel() }
}

internal fun statusReportName(raw: String?): String? = signalName(raw)?.let { safe ->
    if (safe.codePoints().anyMatch { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
            it in 0xD800..0xDFFF }) null
    else safe.substring(0, safe.offsetByCodePoints(0, minOf(64, safe.codePointCount(0, safe.length))))
}

/** A bounded plain-text observation; validated internet access is not inferred from signal strength. */
internal fun rideStatusReportText(kind: RideStatusKind, observed: RideDeviceStatus, zh: Boolean): String {
    if (kind == RideStatusKind.BATTERY) {
        val percent = observed.batteryPercent?.takeIf { it in 0..100 }
        return if (zh) "手機電量，${percent?.let { "百分之 $it" } ?: "電量未知"}。" +
            if (observed.charging) "充電中。" else ""
        else "Phone battery, ${percent?.let { "$it percent" } ?: "level unknown"}." +
            if (observed.charging) " Charging." else ""
    }
    val wifi = kind == RideStatusKind.WIFI
    val signal = if (wifi) observed.wifi else observed.mobile
    val title = if (wifi) "Wi-Fi" else if (zh) "行動網路" else "Mobile network"
    val availability = when (signal.availability) {
        SignalAvailability.READY -> if (zh) if (wifi) "已連線" else "訊號觀察" else if (wifi) "connected" else "signal observation"
        SignalAvailability.OFF -> if (zh) "已關閉" else "off"
        SignalAvailability.DISCONNECTED -> if (zh) "未連線" else "disconnected"
        SignalAvailability.NO_SIM -> if (zh) "無 SIM" else "no SIM"
        SignalAvailability.UNSUPPORTED -> if (zh) "不支援" else "unsupported"
        SignalAvailability.UNAVAILABLE -> if (zh) "狀態無法取得" else "status unavailable"
    }
    if (signal.availability != SignalAvailability.READY) return if (zh) "$title，$availability。" else "$title, $availability."
    val level = signal.level?.takeIf { it in 0..4 }
    val strength = if (zh) level?.let { "訊號 $it 格，最高四格" } ?: "訊號強度未知"
        else level?.let { "signal $it out of 4" } ?: "signal strength unknown"
    val name = statusReportName(signal.name) ?: when {
        wifi && signal.nameIssue == SignalNameIssue.PERMISSION_REQUIRED -> if (zh) "名稱未授權" else "name permission not granted"
        wifi && signal.nameIssue == SignalNameIssue.LOCATION_OFF -> if (zh) "定位已關閉，名稱無法取得" else "location off, name unavailable"
        wifi -> if (zh) "名稱無法取得" else "name unavailable"
        else -> if (zh) "業者名稱無法取得" else "carrier name unavailable"
    }
    // 64 Unicode code points per name plus fixed wording stays well below native TTS input bounds.
    return if (zh) "$title，$availability。$strength。$name。" else "$title, $availability. $strength. $name."
}
