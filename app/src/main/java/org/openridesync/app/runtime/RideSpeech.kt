package org.openridesync.app.runtime

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*
import java.util.UUID

internal enum class RideSpeechError { UNAVAILABLE, INTERRUPTED, TIMEOUT }
internal class RideSpeechFailure(val code: RideSpeechError) : Exception(code.name)

/** Bounded native speech for the current ride page. Text is never written to diagnostics. */
internal suspend fun speakRideLines(context: Context, lines: List<String>, journal: DiagnosticJournal?,
    operation: String, valid: () -> Boolean) = withContext(Dispatchers.Main.immediate) {
    currentCoroutineContext().ensureActive()
    if (!valid()) throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
    require(lines.isNotEmpty() && lines.all { it.isNotBlank() && it.length <= TextToSpeech.getMaxSpeechInputLength() })
    val audio = context.getSystemService(AudioManager::class.java)
        ?: throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
    val notifications = context.getSystemService(NotificationManager::class.java)
    val interrupted = CompletableDeferred<Unit>()
    fun permitted() = !interrupted.isCompleted && valid() &&
        audio.mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION) &&
        notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    if (!permitted()) throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) interrupted.completeExceptionally(RideSpeechFailure(RideSpeechError.INTERRUPTED))
        }.build()
    var engine: TextToSpeech? = null
    var gate: Job? = null
    journal?.record(Component.SDK, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
    try {
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        gate = launch {
            while (isActive) {
                if (!permitted()) {
                    interrupted.completeExceptionally(RideSpeechFailure(RideSpeechError.INTERRUPTED))
                    break
                }
                delay(100)
            }
        }
        val initialized = CompletableDeferred<Int>()
        engine = TextToSpeech(context.applicationContext) { initialized.complete(it) }
        val initStatus = withTimeout(10_000) {
            kotlinx.coroutines.selects.select<Int> {
                initialized.onAwait { it }
                interrupted.onAwait { throw RideSpeechFailure(RideSpeechError.INTERRUPTED) }
            }
        }
        if (initStatus != TextToSpeech.SUCCESS)
            throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
        val tts = requireNotNull(engine)
        if (tts.setLanguage(context.resources.configuration.locales[0]) < 0)
            throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
        if (tts.setAudioAttributes(attributes) != TextToSpeech.SUCCESS)
            throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
        withTimeout(120_000) { for (line in lines) {
            if (!permitted()) throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            val status = tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
                @Deprecated("Platform callback") override fun onError(utteranceId: String?) {
                    if (utteranceId == id) done.completeExceptionally(RideSpeechFailure(RideSpeechError.UNAVAILABLE))
                }
                override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id) done.completeExceptionally(RideSpeechFailure(RideSpeechError.INTERRUPTED))
                }
            })
            if (status != TextToSpeech.SUCCESS || tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS)
                throw RideSpeechFailure(RideSpeechError.UNAVAILABLE)
            withTimeout(60_000) {
                kotlinx.coroutines.selects.select<Unit> {
                    done.onAwait { }
                    interrupted.onAwait { throw RideSpeechFailure(RideSpeechError.INTERRUPTED) }
                }
            }
        } }
        currentCoroutineContext().ensureActive()
        if (!permitted()) throw RideSpeechFailure(RideSpeechError.INTERRUPTED)
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.SUCCEEDED, ErrorCode.NONE)
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.TIMEOUT)
        throw RideSpeechFailure(RideSpeechError.TIMEOUT)
    } catch (error: CancellationException) {
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.CANCELLED, ErrorCode.CANCELLED)
        throw error
    } catch (error: Exception) {
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.UNAVAILABLE)
        throw (error as? RideSpeechFailure ?: RideSpeechFailure(RideSpeechError.UNAVAILABLE))
    } finally {
        gate?.cancel()
        runCatching { engine?.stop() }; runCatching { engine?.shutdown() }
        runCatching { audio.abandonAudioFocusRequest(focus) }
    }
}
