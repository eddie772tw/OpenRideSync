package org.openridesync.app.runtime

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.weather.WeatherSpeechModeTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/** Production speech lifetime with synthetic text and platform TTS callbacks; not audible proof. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [WeatherSpeechModeTest.ReadySpeechShadow::class])
class RideSpeechTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(context.resources.configuration.locales[0])
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun explicitLinesCompleteAfterCallbacksAndReleaseFocus() = runTest(dispatcher) {
        val lines = listOf("Synthetic sender", "Synthetic preview")
        var completed = false
        val job = launch {
            speakRideLines(context, lines, null, "synthetic") { true }
            completed = true
        }
        runCurrent()
        assertFalse(completed)
        val speech = shadowOf(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        runCurrent()
        assertFalse(completed)
        repeat(2) { shadowOf(Looper.getMainLooper()).idle(); runCurrent() }
        assertTrue(completed)
        job.join()
        assertEquals(lines, speech.spokenTextList)
        assertTrue(speech.isStopped)
        assertTrue(speech.isShutdown)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun cancellationWhileInitializingCannotSpeakFromLateCallback() = runTest(dispatcher) {
        var completed = false
        val job = launch {
            speakRideLines(context, listOf("Synthetic preview"), null, "synthetic") { true }
            completed = true
        }
        runCurrent()
        val speech = shadowOf(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        job.cancelAndJoin()
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertFalse(completed)
        assertTrue(speech.spokenTextList.isEmpty())
        assertTrue(speech.isStopped)
        assertTrue(speech.isShutdown)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun staleSourceBetweenUtterancesStopsWithoutSpeakingReplacement() = runTest(dispatcher) {
        var valid = true
        var failure: RideSpeechError? = null
        val job = launch {
            try { speakRideLines(context, listOf("Synthetic sender", "Synthetic preview"), null, "synthetic") { valid } }
            catch (error: RideSpeechFailure) { failure = error.code }
        }
        runCurrent()
        val speech = shadowOf(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        runCurrent()
        assertEquals(listOf("Synthetic sender"), speech.spokenTextList)
        valid = false
        advanceTimeBy(100); runCurrent()
        job.join()
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(RideSpeechError.INTERRUPTED, failure)
        assertEquals(listOf("Synthetic sender"), speech.spokenTextList)
        assertTrue(speech.isShutdown)
    }

    @Test fun focusLossDuringInitializationRejectsEvenAConcurrentSuccessfulInit() = runTest(dispatcher) {
        var failure: RideSpeechError? = null
        val job = launch {
            try { speakRideLines(context, listOf("Synthetic private preview"), null, "synthetic") { true } }
            catch (error: RideSpeechFailure) { failure = error.code }
        }
        runCurrent()
        val speech = shadowOf(requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance()))
        requireNotNull(shadowOf(audio).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        // Both callbacks arrive before the suspended coroutine resumes; no first utterance may escape.
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        runCurrent()
        job.join()
        shadowOf(Looper.getMainLooper()).idle(); runCurrent()
        assertEquals(RideSpeechError.INTERRUPTED, failure)
        assertTrue(speech.spokenTextList.isEmpty())
        assertTrue(speech.isStopped)
        assertTrue(speech.isShutdown)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun dndAndCallAndExpiredOwnerNeverStartSpeech() = runTest(dispatcher) {
        suspend fun rejected(valid: Boolean = true) {
            try {
                speakRideLines(context, listOf("Synthetic preview"), null, "synthetic") { valid }
                fail("Speech must be rejected")
            } catch (error: RideSpeechFailure) { assertEquals(RideSpeechError.INTERRUPTED, error.code) }
            assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        }
        rejected(false)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        rejected()
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        rejected()
    }

    @Test fun deniedFocusDoesNotInitializeEngineOrClaimSpokenSuccess() = runTest(dispatcher) {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        try {
            speakRideLines(context, listOf("Synthetic preview"), null, "synthetic") { true }
            fail("Focus denial must fail")
        } catch (error: RideSpeechFailure) { assertEquals(RideSpeechError.INTERRUPTED, error.code) }
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }
}
