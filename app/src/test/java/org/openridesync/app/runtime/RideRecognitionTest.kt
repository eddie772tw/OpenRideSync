package org.openridesync.app.runtime

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Production bridge with the bundled platform shadow and synthetic text; no microphone or service. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RideRecognitionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        audio.isMicrophoneMute = false
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "synthetic.recognizer"
                name = "SyntheticRecognitionService"
                enabled = true
                exported = true
            }
        })
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service",
            "synthetic.recognizer/SyntheticRecognitionService")
        ShadowSpeechRecognizer.reset()
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    private fun CoroutineScope.recognize(languageTag: String? = null, valid: () -> Boolean = { true }) = async {
        runCatching { recognizeRideMessage(context, null, "synthetic-recognition", languageTag = languageTag, valid = valid) }
    }

    private fun TestScope.startedEngine(): ShadowSpeechRecognizer {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        return shadowOf(requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()))
    }

    private fun results(vararg values: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(values.toList()))
    }

    @Test fun onlyTheFirstNonblankFinalResultCompletesAndReleasesTheProviderAndFocus() = runTest(dispatcher) {
        val task = recognize()
        val engine = startedEngine()
        val intent = requireNotNull(engine.lastRecognizerIntent)
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals(resolveRecognitionLanguage(context, null), intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE))
        engine.triggerOnPartialResults(results("Synthetic partial"))
        runCurrent()
        assertFalse(task.isCompleted)
        engine.triggerOnResults(results(" ", " Synthetic final ", "Ignored alternative"))
        runCurrent()
        assertEquals("Synthetic final", task.await().getOrThrow())
        assertTrue(engine.isDestroyed)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun selectedRecognitionLanguageOnlyChangesRecognitionIntentAndStillRequiresMicrophonePermission() = runTest(dispatcher) {
        val appLocale = context.resources.configuration.locales[0]
        val task = recognize("yue-Hant-HK")
        val engine = startedEngine()
        assertEquals("yue-Hant-HK", requireNotNull(engine.lastRecognizerIntent).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(appLocale, context.resources.configuration.locales[0])
        engine.triggerOnResults(results("Synthetic Cantonese result"))
        runCurrent()
        assertEquals("Synthetic Cantonese result", task.await().getOrThrow())
        ShadowSpeechRecognizer.reset()
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val denied = runCatching { recognizeRideMessage(context, null, "synthetic-denied", languageTag = "cmn-Hant-TW") { true } }
        assertEquals(RideRecognitionError.PERMISSION_REQUIRED, (denied.exceptionOrNull() as RideRecognitionFailure).code)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test fun malformedLanguageNeverCreatesRecognizerOrReturnsDraft() = runTest(dispatcher) {
        val result = recognize("en_US").await()
        assertEquals(RideRecognitionError.UNAVAILABLE, (result.exceptionOrNull() as RideRecognitionFailure).code)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test fun resolvedProviderCandidateRetainsItsRawScriptCaseInTheRecognitionRequest() = runTest(dispatcher) {
        val task = recognize("yue-hant-HK")
        val engine = startedEngine()
        assertEquals("yue-hant-HK", requireNotNull(engine.lastRecognizerIntent).getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        engine.triggerOnResults(results("Synthetic final")); runCurrent()
        assertEquals("Synthetic final", task.await().getOrThrow())
    }

    @Test fun cancellationReleasesTheOwnerAndLateCallbacksCannotCompleteOrDestroyItsSuccessor() = runTest(dispatcher) {
        val first = recognize()
        val old = startedEngine()
        val blocked = runCatching { recognizeRideMessage(context, null, "synthetic-overlap") { true } }
        assertEquals(RideRecognitionError.BUSY, (blocked.exceptionOrNull() as RideRecognitionFailure).code)
        first.cancelAndJoin()
        assertTrue(old.isDestroyed)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        val second = recognize()
        val fresh = startedEngine()
        old.triggerOnResults(results("Late private text"))
        old.triggerOnError(SpeechRecognizer.ERROR_AUDIO)
        runCurrent()
        assertFalse(second.isCompleted)
        assertFalse(fresh.isDestroyed)
        fresh.triggerOnResults(results("Synthetic successor"))
        runCurrent()
        assertEquals("Synthetic successor", second.await().getOrThrow())
        assertTrue(fresh.isDestroyed)
    }

    @Test fun revokedPermissionAndExpiredOwnerRejectEvenAConcurrentFinalResult() = runTest(dispatcher) {
        var valid = true
        val task = recognize { valid }
        val engine = startedEngine()
        valid = false
        engine.triggerOnResults(results("Obsolete private text"))
        runCurrent()
        assertEquals(RideRecognitionError.INTERRUPTED, (task.await().exceptionOrNull() as RideRecognitionFailure).code)
        assertTrue(engine.isDestroyed)
        valid = true
        val second = recognize { valid }
        val fresh = startedEngine()
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        fresh.triggerOnResults(results("Permission revoked text"))
        runCurrent()
        assertEquals(RideRecognitionError.PERMISSION_REQUIRED, (second.await().exceptionOrNull() as RideRecognitionFailure).code)
        assertTrue(fresh.isDestroyed)
    }

    @Test fun providerErrorsEmptyAndOversizedResultsNeverReturnADraft() = runTest(dispatcher) {
        for ((provider, expected) in listOf(
            SpeechRecognizer.ERROR_NO_MATCH to RideRecognitionError.NO_MATCH,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to RideRecognitionError.PERMISSION_REQUIRED,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to RideRecognitionError.BUSY,
            SpeechRecognizer.ERROR_NETWORK to RideRecognitionError.UNAVAILABLE)) {
            val task = recognize()
            val engine = startedEngine()
            engine.triggerOnError(provider)
            runCurrent()
            assertEquals(expected, (task.await().exceptionOrNull() as RideRecognitionFailure).code)
            assertTrue(engine.isDestroyed)
        }
        for (bundle in listOf(Bundle(), results(" ", ""), results("x".repeat(2_001)))) {
            val task = recognize()
            val engine = startedEngine()
            engine.triggerOnResults(bundle)
            runCurrent()
            assertEquals(RideRecognitionError.NO_MATCH, (task.await().exceptionOrNull() as RideRecognitionFailure).code)
            assertTrue(engine.isDestroyed)
        }
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun expiredOwnerCancelsAProviderThatNeverSendsAnotherCallback() = runTest(dispatcher) {
        var valid = true
        val task = recognize { valid }
        val engine = startedEngine()
        valid = false
        advanceTimeBy(100)
        runCurrent()
        assertEquals(RideRecognitionError.INTERRUPTED, (task.await().exceptionOrNull() as RideRecognitionFailure).code)
        assertTrue(engine.isDestroyed)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun timeoutAndAudioFocusLossDestroyRecognitionWithoutReturningText() = runTest(dispatcher) {
        val task = recognize()
        val engine = startedEngine()
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(RideRecognitionError.TIMEOUT, (task.await().exceptionOrNull() as RideRecognitionFailure).code)
        assertTrue(engine.isDestroyed)
        val second = recognize()
        val fresh = startedEngine()
        requireNotNull(shadowOf(audio).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        fresh.triggerOnResults(results("Concurrent final"))
        runCurrent()
        assertEquals(RideRecognitionError.INTERRUPTED, (second.await().exceptionOrNull() as RideRecognitionFailure).code)
        assertTrue(fresh.isDestroyed)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun permissionDndCallsMicrophoneMuteAndMissingServiceNeverCreateTheRecognizer() = runTest(dispatcher) {
        suspend fun rejected(expected: RideRecognitionError, valid: Boolean = true) {
            val result = runCatching { recognizeRideMessage(context, null, "synthetic-precheck") { valid } }
            assertEquals(expected, (result.exceptionOrNull() as RideRecognitionFailure).code)
            assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        }
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        rejected(RideRecognitionError.PERMISSION_REQUIRED)
        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        rejected(RideRecognitionError.INTERRUPTED, false)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        rejected(RideRecognitionError.INTERRUPTED)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        rejected(RideRecognitionError.INTERRUPTED)
        audio.mode = AudioManager.MODE_RINGTONE
        rejected(RideRecognitionError.INTERRUPTED)
        audio.mode = AudioManager.MODE_NORMAL
        audio.isMicrophoneMute = true
        rejected(RideRecognitionError.INTERRUPTED)
        audio.isMicrophoneMute = false
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        rejected(RideRecognitionError.INTERRUPTED)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        shadowOf(context.packageManager).removeResolveInfosForIntent(Intent(RecognitionService.SERVICE_INTERFACE), "synthetic.recognizer")
        rejected(RideRecognitionError.UNAVAILABLE)
    }
}
