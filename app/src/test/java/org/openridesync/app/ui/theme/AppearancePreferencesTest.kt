package org.openridesync.app.ui.theme

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
class AppearancePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val stored get() = context.getSharedPreferences("phone_appearance", Context.MODE_PRIVATE)

    @Before fun reset() { stored.edit().clear().commit() }

    @Test fun defaultsAndUnknownValuesFollowSystem() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStored(null))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStored("unknown"))
        assertFalse(AppearanceMode.SYSTEM.resolveDark(false))
        assertTrue(AppearanceMode.SYSTEM.resolveDark(true))
        assertFalse(AppearanceMode.LIGHT.resolveDark(true))
        assertTrue(AppearanceMode.DARK.resolveDark(false))
        AppearancePreferences(context).use { assertEquals(AppearanceMode.SYSTEM, it.mode.value) }
    }

    @Test fun selectedModeSurvivesOwnerRecreation() {
        AppearancePreferences(context).use { preferences ->
            preferences.setMode(AppearanceMode.DARK)
            assertEquals(AppearanceMode.DARK, preferences.mode.value)
        }
        AppearancePreferences(context).use { assertEquals(AppearanceMode.DARK, it.mode.value) }
    }

    @Test fun externalUpdatesAndClearAreObservedUntilClosed() {
        val preferences = AppearancePreferences(context)
        stored.edit().putString("mode", "LIGHT").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppearanceMode.LIGHT, preferences.mode.value)
        stored.edit().clear().commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppearanceMode.SYSTEM, preferences.mode.value)
        preferences.close()
        preferences.close()
        stored.edit().putString("mode", "DARK").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AppearanceMode.SYSTEM, preferences.mode.value)
    }

    @Test fun malformedPersistedTypeFallsBackWithoutCrashing() {
        stored.edit().putInt("mode", 7).commit()
        AppearancePreferences(context).use { assertEquals(AppearanceMode.SYSTEM, it.mode.value) }
    }
}
