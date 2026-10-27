package org.openridesync.app.runtime

import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** Explicit caller actions only. Readback describes platform state, never audible success. */
internal class RideVolume(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val notifications = context.applicationContext.getSystemService(NotificationManager::class.java)
    enum class Stream { MEDIA, CALL }
    enum class Direction { UP, DOWN }
    enum class Status { OBSERVED, REQUESTED, AT_BOUND, FIXED_VOLUME, PERMISSION_REQUIRED, UNAVAILABLE }
    data class State(val stream: Stream, val platformStream: Int, val current: Int, val minimum: Int,
                     val maximum: Int, val percentage: Int, val level: Int, val fixed: Boolean,
                     val interruptionFilter: Int)
    data class Result(val status: Status, val state: State?)
    data class MuteResult(val status: Status, val requestedMuted: Boolean, val interruptionFilter: Int?)

    @Suppress("DEPRECATION")
    private fun platformStream(stream: Stream): Int {
        if (stream == Stream.MEDIA) return AudioManager.STREAM_MUSIC
        val manager = requireNotNull(audio)
        val sco = if (Build.VERSION.SDK_INT >= 33)
            manager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        else manager.isBluetoothScoOn
        return if (sco) 6 else AudioManager.STREAM_VOICE_CALL // OEM stream 6 is Bluetooth SCO.
    }

    fun read(stream: Stream): Result = try {
        val manager = requireNotNull(audio)
        val selected = platformStream(stream)
        val current = manager.getStreamVolume(selected)
        val maximum = manager.getStreamMaxVolume(selected)
        val minimum = manager.getStreamMinVolume(selected)
        val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val percent = percentage(current, maximum)
        Result(Status.OBSERVED, State(stream, selected, current, minimum, maximum,
            percent, level(percent), manager.isVolumeFixed, filter))
    } catch (_: SecurityException) { Result(Status.PERMISSION_REQUIRED, null) }
      catch (_: RuntimeException) { Result(Status.UNAVAILABLE, null) }

    suspend fun adjust(stream: Stream, direction: Direction): Result {
        currentCoroutineContext().ensureActive()
        val before = read(stream)
        val state = before.state ?: return before
        if (direction == Direction.UP && state.interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
            val unmute = setMuted(false)
            return Result(unmute.status, read(stream).state)
        }
        if (state.fixed) return Result(Status.FIXED_VOLUME, state)
        val target = try { target(state.current, state.minimum, state.maximum, direction) }
            catch (_: IllegalArgumentException) { return Result(Status.UNAVAILABLE, state) }
        if (target == state.current) return Result(Status.AT_BOUND, state)
        return try {
            val manager = requireNotNull(audio)
            currentCoroutineContext().ensureActive()
            if (needsAdjustWorkaround(Build.MANUFACTURER, Build.VERSION.SDK_INT)) {
                val adjustment = if (target > state.current) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                repeat(kotlin.math.abs(target - state.current)) { manager.adjustStreamVolume(state.platformStream, adjustment, 0) }
            } else manager.setStreamVolume(state.platformStream, target, 0)
            val after = read(stream)
            Result(if (after.state != null) Status.REQUESTED else after.status, after.state)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: SecurityException) { Result(Status.PERMISSION_REQUIRED, read(stream).state) }
          catch (_: RuntimeException) { Result(Status.UNAVAILABLE, read(stream).state) }
    }

    /** OEM mute is DND NONE, not AudioManager stream mute. Caller must make this choice explicit.
     * Target35+ normally changes this app's implicit Zen rule; ALL cannot clear others' rules. */
    suspend fun setMuted(muted: Boolean): MuteResult {
        currentCoroutineContext().ensureActive()
        val manager = notifications ?: return MuteResult(Status.UNAVAILABLE, muted, null)
        fun filter(): Int? = try { manager.currentInterruptionFilter } catch (_: RuntimeException) { null }
        try {
            if (muted) delay(400)
            currentCoroutineContext().ensureActive()
            val observed = manager.currentInterruptionFilter
            // OEM rechecks after its delay. Already-NONE needs no policy write or permission.
            if (muted && observed == NotificationManager.INTERRUPTION_FILTER_NONE)
                return MuteResult(Status.OBSERVED, true, observed)
            if (!manager.isNotificationPolicyAccessGranted) return MuteResult(Status.PERMISSION_REQUIRED, muted, filter())
            manager.setInterruptionFilter(if (muted) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
            return MuteResult(Status.REQUESTED, muted, filter())
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: SecurityException) { return MuteResult(Status.PERMISSION_REQUIRED, muted, filter()) }
          catch (_: RuntimeException) { return MuteResult(Status.UNAVAILABLE, muted, filter()) }
    }

    companion object {
        fun selectStream(cellularCallIdle: Boolean, messageAppCallActive: Boolean): Stream =
            if (!cellularCallIdle || messageAppCallActive) Stream.CALL else Stream.MEDIA
        fun percentage(current: Int, maximum: Int): Int = if (maximum <= 0) 0 else
            (current.toDouble() / maximum * 100).toInt().coerceIn(0, 100)
        fun level(percentage: Int): Int = if (percentage <= 0) 0 else ((percentage.coerceAtMost(100) - 1) * 5 / 100) + 1
        fun target(current: Int, minimum: Int, maximum: Int, direction: Direction): Int {
            require(minimum >= 0 && maximum >= minimum && current in minimum..maximum)
            val step = (maximum / 10).coerceAtLeast(1)
            return (current.toLong() + if (direction == Direction.UP) step else -step).coerceIn(minimum.toLong(), maximum.toLong()).toInt()
        }
        fun needsAdjustWorkaround(manufacturer: String, sdk: Int): Boolean {
            val name = manufacturer.lowercase(Locale.ROOT)
            return (name.contains("realme") && sdk <= 35) ||
                ((name.contains("oppo") || name.contains("oneplus")) && sdk <= 34)
        }
    }
}
