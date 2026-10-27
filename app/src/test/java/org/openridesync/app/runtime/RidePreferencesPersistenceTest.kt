package org.openridesync.app.runtime

import android.app.Application
import android.content.Context
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RidePreferencesPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val stored get() = context.getSharedPreferences("ride", Context.MODE_PRIVATE)
    @Before fun reset() { stored.edit().clear().commit() }

    @Test fun allSettingsSurviveRecreationAndUpdateAnotherLiveOwner() {
        RidePreferences(context).use { first ->
            RidePreferences(context).use { second ->
                second.setFunctionOrder(MainMenuSelection.Item.entries.reversed())
                second.setWeatherSpeech(WeatherSpeechMode.SUMMARY)
                second.setAutoReturn(true)
                second.setIncomingMessages(IncomingMessageMode.AUTO_READ)
                second.setScreenAwake(ScreenAwakeMode.ONLY_CHARGING)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(MainMenuSelection.Item.entries.reversed(), first.functionOrder.value)
                assertEquals(WeatherSpeechMode.SUMMARY, first.weatherSpeech.value)
                assertTrue(first.autoReturn.value)
                assertEquals(IncomingMessageMode.AUTO_READ, first.incomingMessages.value)
                assertEquals(ScreenAwakeMode.ONLY_CHARGING, first.screenAwake.value)
            }
        }
        RidePreferences(context).use {
            assertEquals(MainMenuSelection.Item.entries.reversed(), it.functionOrder.value)
            assertEquals(WeatherSpeechMode.SUMMARY, it.weatherSpeech.value)
            assertTrue(it.autoReturn.value)
            assertEquals(IncomingMessageMode.AUTO_READ, it.incomingMessages.value)
            assertEquals(ScreenAwakeMode.ONLY_CHARGING, it.screenAwake.value)
        }
    }

    @Test fun malformedStoredTypesUseSafeDefaultsAndClearUpdatesLiveOwner() {
        stored.edit().putInt("function_order", 1).putBoolean("weather_speech", true)
            .putString("auto_return", "wrong type").putInt("incoming_messages", 1).putInt("screen_awake", 1).commit()
        RidePreferences(context).use {
            assertEquals(MainMenuSelection.Item.entries, it.functionOrder.value)
            assertEquals(WeatherSpeechMode.FULL, it.weatherSpeech.value)
            assertFalse(it.autoReturn.value)
            assertEquals(IncomingMessageMode.ASK_TO_LISTEN, it.incomingMessages.value)
            assertEquals(ScreenAwakeMode.OFF, it.screenAwake.value)
            it.setAutoReturn(true)
            it.setWeatherSpeech(WeatherSpeechMode.OFF)
            it.setIncomingMessages(IncomingMessageMode.IGNORE)
            it.setScreenAwake(ScreenAwakeMode.ON)
            stored.edit().clear().commit()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(it.autoReturn.value)
            assertEquals(WeatherSpeechMode.FULL, it.weatherSpeech.value)
            assertEquals(IncomingMessageMode.ASK_TO_LISTEN, it.incomingMessages.value)
            assertEquals(ScreenAwakeMode.OFF, it.screenAwake.value)
        }
    }

    @Test fun incomingMessageModesPersistAcrossOwnersAndRejectUnknownStoredNames() {
        RidePreferences(context).use { first ->
            RidePreferences(context).use { second ->
                IncomingMessageMode.entries.forEach { mode ->
                    first.setIncomingMessages(mode)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(mode, second.incomingMessages.value)
                    RidePreferences(context).use { restored -> assertEquals(mode, restored.incomingMessages.value) }
                }
                stored.edit().putString("incoming_messages", "NOT_A_MODE").commit()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(IncomingMessageMode.ASK_TO_LISTEN, first.incomingMessages.value)
                assertEquals(IncomingMessageMode.ASK_TO_LISTEN, second.incomingMessages.value)
            }
        }
    }

    @Test fun screenAwakeChoicesPersistAndUnknownStoredNamesStayOff() {
        RidePreferences(context).use { first ->
            RidePreferences(context).use { second ->
                ScreenAwakeMode.entries.forEach { mode ->
                    first.setScreenAwake(mode)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(mode, second.screenAwake.value)
                    RidePreferences(context).use { restored -> assertEquals(mode, restored.screenAwake.value) }
                }
                stored.edit().putString("screen_awake", "UNKNOWN").commit()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(ScreenAwakeMode.OFF, first.screenAwake.value)
                assertEquals(ScreenAwakeMode.OFF, second.screenAwake.value)
            }
        }
    }
}
