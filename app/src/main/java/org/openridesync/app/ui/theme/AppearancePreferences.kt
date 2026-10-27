package org.openridesync.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phone appearance only. Does not alter the vehicle display or runtime. */
enum class AppearanceMode {
    SYSTEM, LIGHT, DARK;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): AppearanceMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** Own this for the UI host lifetime and close it when that owner is disposed. */
class AppearancePreferences(context: Context) : AutoCloseable {
    private val preferences = context.applicationContext.getSharedPreferences("phone_appearance", Context.MODE_PRIVATE)
    private fun read(): AppearanceMode = try {
        AppearanceMode.fromStored(preferences.getString(KEY, null))
    } catch (_: ClassCastException) {
        AppearanceMode.SYSTEM
    }
    private val mutableMode = MutableStateFlow(read())
    val mode: StateFlow<AppearanceMode> = mutableMode.asStateFlow()
    private var closed = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed && (key == KEY || key == null)) mutableMode.value = read()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        mutableMode.value = read()
    }

    fun setMode(mode: AppearanceMode) {
        check(!closed) { "AppearancePreferences is closed" }
        preferences.edit().putString(KEY, mode.name).apply()
        mutableMode.value = read()
    }

    override fun close() {
        if (!closed) {
            closed = true
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private companion object { const val KEY = "mode" }
}

@Composable
fun rememberAppearancePreferences(): AppearancePreferences {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { AppearancePreferences(context) }
    DisposableEffect(preferences) { onDispose { preferences.close() } }
    return preferences
}
