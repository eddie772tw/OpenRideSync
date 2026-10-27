package org.openridesync.app.runtime

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class WeatherSpeechMode { FULL, SUMMARY, OFF;
    companion object {
        fun parse(value: String?): WeatherSpeechMode = entries.find { it.name == value } ?: FULL
    }
}

/** Notification handling for an initialized SAB ride. Reply remains a separate feature. */
internal enum class IncomingMessageMode { ASK_TO_LISTEN, AUTO_READ, IGNORE;
    companion object {
        fun parse(value: String?): IncomingMessageMode = entries.find { it.name == value } ?: ASK_TO_LISTEN
    }
}

internal enum class ScreenAwakeMode { ON, ONLY_CHARGING, OFF;
    companion object {
        fun parse(value: String?): ScreenAwakeMode = entries.find { it.name == value } ?: OFF
    }
}

/** Only weather informational speech; startup/readiness prompts are deliberately unaffected. */
internal fun weatherSpeechLines(lines: List<String>, mode: WeatherSpeechMode): List<String> = when (mode) {
    WeatherSpeechMode.FULL -> lines.toList()
    WeatherSpeechMode.SUMMARY -> lines.take(2) // Location introduction and first forecast period.
    WeatherSpeechMode.OFF -> emptyList()
}

/** App-owned preferences; no reading or migration of another app's private preferences. */
internal class RidePreferences(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences("ride", Context.MODE_PRIVATE)
    private val value = MutableStateFlow((preferences.all["auto_return"] as? Boolean ?: false))
    val autoReturn = value.asStateFlow()
    private val order = MutableStateFlow(parseFunctionOrder((preferences.all["function_order"] as? String)))
    val functionOrder = order.asStateFlow()
    private val speech = MutableStateFlow(WeatherSpeechMode.parse((preferences.all["weather_speech"] as? String)))
    val weatherSpeech = speech.asStateFlow()
    private val messages = MutableStateFlow(IncomingMessageMode.parse(preferences.all["incoming_messages"] as? String))
    val incomingMessages = messages.asStateFlow()
    private val awake = MutableStateFlow(ScreenAwakeMode.parse(preferences.all["screen_awake"] as? String))
    val screenAwake = awake.asStateFlow()
    // Keep a strong reference: SharedPreferences stores listeners weakly. Also updates alternate wrappers.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    private fun refresh() {
        value.value = (preferences.all["auto_return"] as? Boolean ?: false)
        order.value = parseFunctionOrder((preferences.all["function_order"] as? String))
        speech.value = WeatherSpeechMode.parse((preferences.all["weather_speech"] as? String))
        messages.value = IncomingMessageMode.parse(preferences.all["incoming_messages"] as? String)
        awake.value = ScreenAwakeMode.parse(preferences.all["screen_awake"] as? String)
    }

    override fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener) }

    fun setAutoReturn(enabled: Boolean) {
        preferences.edit().putBoolean("auto_return", enabled).apply()
        refresh()
    }

    fun setFunctionOrder(items: List<MainMenuSelection.Item>) {
        preferences.edit().putString("function_order", normalizeFunctionOrder(items).joinToString(",") { it.name }).apply()
        refresh()
    }

    fun setWeatherSpeech(mode: WeatherSpeechMode) {
        preferences.edit().putString("weather_speech", mode.name).apply()
        refresh()
    }

    fun setIncomingMessages(mode: IncomingMessageMode) {
        preferences.edit().putString("incoming_messages", mode.name).apply()
        refresh()
    }

    fun setScreenAwake(mode: ScreenAwakeMode) {
        preferences.edit().putString("screen_awake", mode.name).apply()
        refresh()
    }
}
