package org.openridesync.app.weather

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.runtime.RidePreferences
import org.openridesync.app.runtime.WeatherSpeechMode
import org.openridesync.honda.wire.WeatherWire
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers

/** Exercises the production speech consumer, replacing only the platform TTS/audio services. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [WeatherSpeechModeTest.ReadySpeechShadow::class])
class WeatherSpeechModeTest {
    // Robolectric's standard TTS shadow already records speech and dispatches utterance callbacks.
    // Its real setAudioAttributes otherwise requires an external TTS service, absent in host tests.
    @Implements(TextToSpeech::class)
    class ReadySpeechShadow : ShadowTextToSpeech() {
        @Implementation
        protected fun setAudioAttributes(attributes: AudioAttributes): Int = TextToSpeech.SUCCESS
    }

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var context: Context
    private lateinit var preferences: RidePreferences
    private lateinit var audio: AudioManager
    private val forecast = ForecastWeather(WeatherWire.WeatherForecast.getDefaultInstance(),
        listOf(1, 3, 5).map { hour -> HourlyWeather(hour,
            WeatherReading(20f + hour, 800, "Synthetic place", "Clear period $hour", "Clear", true)) })

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val base: Context = RuntimeEnvironment.getApplication()
        base.getSharedPreferences("ride", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = RidePreferences(base)
        // Do not call the production application's onCreate: no AuthSession or RideConnection.
        val app = OpenRideSyncApplication()
        ReflectionHelpers.setField(app, "mBase", base)
        ReflectionHelpers.setField(app, "ridePreferences", preferences)
        context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = app
        }
        audio = context.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(context.resources.configuration.locales[0])
    }

    @After fun tearDown() {
        preferences.close()
        Dispatchers.resetMain()
    }

    @Test fun offReturnsWithoutConstructingSpeechEngineOrRequestingAudioFocus() = runTest(dispatcher) {
        preferences.setWeatherSpeech(WeatherSpeechMode.OFF)
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        speakWeatherReport(context, forecast, TemperatureUnit.CELSIUS, null, "synthetic-off") { true }
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNull(shadowOf(audio).lastAudioFocusRequest)
        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun summaryActuallySpeaksOnlyIntroductionAndFirstForecastThenReleasesResources() = runTest(dispatcher) {
        preferences.setWeatherSpeech(WeatherSpeechMode.SUMMARY)
        val job = launch {
            speakWeatherReport(context, forecast, TemperatureUnit.CELSIUS, null, "synthetic-summary") { true }
        }
        runCurrent()
        val tts = requireNotNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val speech = shadowOf(tts)
        speech.onInitListener.onInit(TextToSpeech.SUCCESS)
        runCurrent()
        // The production loop awaits each platform utterance before starting the next one.
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
        }
        assertTrue("Both summary utterances should complete", job.isCompleted)
        job.join()
        val allLines = weatherReportLines(context, forecast, TemperatureUnit.CELSIUS)
        assertEquals(4, allLines.size)
        assertEquals(allLines.take(2), speech.spokenTextList)
        assertNotNull(shadowOf(audio).lastAudioFocusRequest)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
        assertTrue(speech.isStopped)
        assertTrue(speech.isShutdown)
    }
}
