package org.openridesync.app.runtime

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.openridesync.app.media.MediaAccessService
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.*

internal enum class RideRecognitionError { PERMISSION_REQUIRED, UNAVAILABLE, BUSY, INTERRUPTED, TIMEOUT, NO_MATCH }
internal class RideRecognitionFailure(val code: RideRecognitionError) : Exception(code.name)
private val rideRecognitionOwner = Mutex()

/** One bounded system-default recognizer. Final text stays in RAM; the provider may use a network service. */
@SuppressLint("MissingPermission") // blocked() checks RECORD_AUDIO before start and throughout recognition.
internal suspend fun recognizeRideMessage(context: Context, journal: DiagnosticJournal?, operation: String,
    languageTag: String? = null, valid: () -> Boolean): String = withContext(Dispatchers.Main.immediate) {
    currentCoroutineContext().ensureActive()
    if (!rideRecognitionOwner.tryLock()) throw RideRecognitionFailure(RideRecognitionError.BUSY)
    val lifetime = currentCoroutineContext()
    val done = CompletableDeferred<String>()
    var recognizer: SpeechRecognizer? = null
    var audio: AudioManager? = null
    var focus: AudioFocusRequest? = null
    var gate: Job? = null
    var closed = false
    var focusLost = false
    var notifications: NotificationManager? = null
    fun blocked(): RideRecognitionError? = when {
        !valid() || focusLost -> RideRecognitionError.INTERRUPTED
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> RideRecognitionError.PERMISSION_REQUIRED
        audio == null -> RideRecognitionError.UNAVAILABLE
        audio!!.isMicrophoneMute || audio!!.mode != AudioManager.MODE_NORMAL -> RideRecognitionError.INTERRUPTED
        notifications?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL -> RideRecognitionError.INTERRUPTED
        MediaAccessService.callSummary.value.let { it.active || it.incoming } -> RideRecognitionError.INTERRUPTED
        else -> null
    }
    fun fail(code: RideRecognitionError) { done.completeExceptionally(RideRecognitionFailure(code)) }
    try {
        audio = context.getSystemService(AudioManager::class.java)
        notifications = context.getSystemService(NotificationManager::class.java)
        blocked()?.let { throw RideRecognitionFailure(it) }
        val language = resolveRecognitionLanguage(context, languageTag)
        if (!SpeechRecognizer.isRecognitionAvailable(context)) throw RideRecognitionFailure(RideRecognitionError.UNAVAILABLE)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && !closed) { focusLost = true; fail(RideRecognitionError.INTERRUPTED) }
            }.build()
        focus = request
        if (audio!!.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw RideRecognitionFailure(RideRecognitionError.INTERRUPTED)
        blocked()?.let { throw RideRecognitionFailure(it) }
        journal?.record(Component.SDK, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
        val engine = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).also { recognizer = it }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                if (closed || !lifetime.isActive || done.isCompleted) return
                fail(when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RideRecognitionError.PERMISSION_REQUIRED
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RideRecognitionError.BUSY
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RideRecognitionError.NO_MATCH
                    else -> RideRecognitionError.UNAVAILABLE
                })
            }
            override fun onResults(results: Bundle?) {
                if (closed || !lifetime.isActive || done.isCompleted) return
                blocked()?.let { fail(it); return }
                val text = runCatching { results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { !it.isNullOrBlank() }?.trim() }.getOrNull()
                if (text == null || text.length > 2_000) fail(RideRecognitionError.NO_MATCH)
                else done.complete(text)
            }
        })
        blocked()?.let { throw RideRecognitionFailure(it) }
        engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        gate = launch {
            while (isActive && !done.isCompleted) {
                blocked()?.let { fail(it); return@launch }
                delay(100)
            }
        }
        val result = withTimeout(40_000) { done.await() }
        lifetime.ensureActive()
        blocked()?.let { throw RideRecognitionFailure(it) }
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.SUCCEEDED, ErrorCode.NONE)
        result
    } catch (error: TimeoutCancellationException) {
        lifetime.ensureActive()
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.TIMEOUT)
        throw RideRecognitionFailure(RideRecognitionError.TIMEOUT)
    } catch (error: CancellationException) {
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.CANCELLED, ErrorCode.CANCELLED)
        throw error
    } catch (error: Exception) {
        val failure = error as? RideRecognitionFailure ?: RideRecognitionFailure(
            if (error is SecurityException) RideRecognitionError.PERMISSION_REQUIRED else RideRecognitionError.UNAVAILABLE)
        journal?.record(Component.SDK, operation, Event.STATE_CHANGE, Outcome.FAILED, when (failure.code) {
            RideRecognitionError.PERMISSION_REQUIRED -> ErrorCode.PERMISSION_DENIED
            RideRecognitionError.BUSY -> ErrorCode.ALREADY_EXISTS
            RideRecognitionError.INTERRUPTED -> ErrorCode.CANCELLED
            RideRecognitionError.TIMEOUT -> ErrorCode.TIMEOUT
            RideRecognitionError.NO_MATCH -> ErrorCode.NOT_FOUND
            RideRecognitionError.UNAVAILABLE -> ErrorCode.UNAVAILABLE
        })
        throw failure
    } finally {
        closed = true
        gate?.cancel()
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        focus?.let { request -> runCatching { audio?.abandonAudioFocusRequest(request) } }
        rideRecognitionOwner.unlock()
    }
}
