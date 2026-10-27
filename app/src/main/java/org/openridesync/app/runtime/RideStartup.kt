package org.openridesync.app.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import org.openridesync.app.MainActivity
import org.openridesync.app.R
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

internal enum class RideStartupError { NOTIFICATION_FAILED, TTS_INIT_FAILED, TTS_LANGUAGE_UNAVAILABLE, TTS_FAILED, TTS_STOPPED, TIMEOUT }
internal class RideStartupFailure(val error: RideStartupError) : Exception(error.name)

internal fun needsRideStartupWarning(phoneState: Boolean, phoneNumbers: Boolean, hereNotHandled: Boolean) =
    (phoneState && !phoneNumbers) || hereNotHandled

/** OEM warning text/predicate; native limits are 10s initialization and 60s utterance.
 * Completion means onDone, never that the rider heard or understood the speech. */
internal suspend fun runRideStartupWarning(context: Context, journal: DiagnosticJournal?, operation: String,
    hereConsentNotHandled: Boolean): Unit = withContext(Dispatchers.Main.immediate) {
    val app = context.applicationContext
    fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    if (!needsRideStartupWarning(granted(Manifest.permission.READ_PHONE_STATE),
            granted(Manifest.permission.READ_PHONE_NUMBERS), hereConsentNotHandled)) return@withContext
    journal?.record(Component.SDK, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
    var engine: TextToSpeech? = null
    try {
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
                ?: throw RideStartupFailure(RideStartupError.NOTIFICATION_FAILED)
            val channel = "ride_recommended_settings"
            manager.createNotificationChannel(NotificationChannel(channel,
                app.getString(R.string.startup_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val intent = PendingIntent.getActivity(app, 7201,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(7201, Notification.Builder(app, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(app.getString(R.string.startup_title))
                .setContentText(app.getString(R.string.startup_warning))
                .setStyle(Notification.BigTextStyle().bigText(app.getString(R.string.startup_warning)))
                .setContentIntent(intent).setAutoCancel(true).build())
        } catch (_: RuntimeException) { throw RideStartupFailure(RideStartupError.NOTIFICATION_FAILED) }
        val initialized = CompletableDeferred<Int>()
        try { engine = TextToSpeech(app) { initialized.complete(it) } }
        catch (_: RuntimeException) { throw RideStartupFailure(RideStartupError.TTS_INIT_FAILED) }
        if (withTimeout(10_000) { initialized.await() } != TextToSpeech.SUCCESS)
            throw RideStartupFailure(RideStartupError.TTS_INIT_FAILED)
        val tts = requireNotNull(engine)
        val language = tts.setLanguage(app.resources.configuration.locales[0])
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED)
            throw RideStartupFailure(RideStartupError.TTS_LANGUAGE_UNAVAILABLE)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        val listenerStatus = tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) done.completeExceptionally(RideStartupFailure(RideStartupError.TTS_FAILED))
            }
            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == id) done.completeExceptionally(RideStartupFailure(RideStartupError.TTS_STOPPED))
            }
        })
        if (listenerStatus != TextToSpeech.SUCCESS)
            throw RideStartupFailure(RideStartupError.TTS_FAILED)
        if (tts.speak(app.getString(R.string.startup_warning), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
            throw RideStartupFailure(RideStartupError.TTS_FAILED)
        withTimeout(60_000) { done.await() }
        currentCoroutineContext().ensureActive()
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.SUCCEEDED, ErrorCode.NONE)
    } catch (failure: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.TIMEOUT)
        throw RideStartupFailure(RideStartupError.TIMEOUT)
    } catch (failure: CancellationException) {
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.CANCELLED, ErrorCode.CANCELLED)
        throw failure
    } catch (failure: Exception) {
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.UNAVAILABLE)
        throw (failure as? RideStartupFailure ?: RideStartupFailure(RideStartupError.TTS_FAILED))
    } finally {
        // Late callbacks can complete only their private Deferred; they cannot advance a page.
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
    }
}
