package org.openridesync.app.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** Simulates commit's important failure case: memory changed even though persistence reports failure. */
internal fun failingQuickReplyContext(context: Context): Context {
    val stored = context.getSharedPreferences(QuickReplyPreferences.FILE, Context.MODE_PRIVATE)
    val failing = object : SharedPreferences by stored {
        override fun edit(): SharedPreferences.Editor {
            val editor = stored.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value)
                    return this
                }
                override fun commit(): Boolean { editor.commit(); return false }
            }
        }
    }
    return object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == QuickReplyPreferences.FILE) failing else super.getSharedPreferences(name, mode)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class QuickReplyPreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val stored get() = context.getSharedPreferences(QuickReplyPreferences.FILE, Context.MODE_PRIVATE)
    @Before fun reset() { stored.edit().clear().commit() }

    private fun localized(tag: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    @Test fun defaultsRemainIdentitiesAcrossLocalesWhileUserTextAndOrderingSurviveReopen() {
        val initial = QuickReplyPreferences.defaults
        QuickReplyPreferences(context).use { preferences ->
            assertEquals((1..5).map { QuickReplyEntry.Default(it) }, preferences.entries.value)
            assertFalse(stored.contains(QuickReplyPreferences.KEY)) // Loading must never write.
            assertEquals("I am driving.", quickReplyTexts(localized("en-US"), initial).first())
            assertEquals("我正在駕駛中。", quickReplyTexts(localized("zh-TW"), initial).first())
            val changed = listOf(QuickReplyEntry.Default(3), QuickReplyEntry.User("Synthetic user text"),
                QuickReplyEntry.Default(1), QuickReplyEntry.Default(5), QuickReplyEntry.Default(4), QuickReplyEntry.Default(2))
            assertTrue(preferences.setEntries(changed)) // A valid sixth entry is preserved, not truncated to five.
            assertEquals(changed, preferences.entries.value)
        }
        QuickReplyPreferences(context).use { reopened ->
            assertEquals(6, reopened.entries.value.size)
            assertEquals(QuickReplyEntry.Default(3), reopened.entries.value.first())
            assertEquals("Synthetic user text", quickReplyTexts(localized("zh-TW"), reopened.entries.value)[1])
            assertEquals("I am running late.", quickReplyTexts(localized("en-US"), reopened.entries.value)[0])
            assertEquals("我會稍晚抵達。", quickReplyTexts(localized("zh-TW"), reopened.entries.value)[0])
        }
    }

    @Test fun malformedStorageIsKeptUntilExplicitDefaultRecovery() {
        val malformed = listOf("not JSON", "{}", "{\"version\":2,\"entries\":[]}",
            "{\"version\":1,\"entries\":[{\"type\":\"default\",\"order\":6}]}",
            "{\"version\":1,\"entries\":[{\"type\":\"user\",\"text\":7}]}",
            "{\"version\":1,\"entries\":[]} trailing")
        malformed.forEach { raw ->
            stored.edit().putString(QuickReplyPreferences.KEY, raw).commit()
            QuickReplyPreferences(context).use { preferences ->
                assertTrue(preferences.invalidStorage.value)
                assertTrue(preferences.entries.value.isEmpty())
                assertEquals(raw, stored.getString(QuickReplyPreferences.KEY, null))
                assertFalse(preferences.setEntries(listOf(QuickReplyEntry.User("Synthetic replacement"))))
                assertEquals(raw, stored.getString(QuickReplyPreferences.KEY, null))
                assertTrue(preferences.setEntries(QuickReplyPreferences.defaults))
                assertFalse(preferences.invalidStorage.value)
                assertEquals(QuickReplyPreferences.defaults, preferences.entries.value)
            }
        }
        stored.edit().putInt(QuickReplyPreferences.KEY, 7).commit()
        QuickReplyPreferences(context).use {
            assertTrue(it.invalidStorage.value)
            assertEquals(7, stored.getInt(QuickReplyPreferences.KEY, 0))
        }
    }

    @Test fun invalidInputsDoNotMutateStorageAndValidLengthsAreNotTrimmed() {
        QuickReplyPreferences(context).use { preferences ->
            val valid = listOf(QuickReplyEntry.User("  Synthetic text  "), QuickReplyEntry.User("x".repeat(2000)))
            assertTrue(preferences.setEntries(valid))
            val raw = stored.getString(QuickReplyPreferences.KEY, null)
            listOf(listOf(QuickReplyEntry.Default(0)), listOf(QuickReplyEntry.User("   ")),
                listOf(QuickReplyEntry.User("x".repeat(2001))), listOf(QuickReplyEntry.User("a\u0000b")),
                List(257) { QuickReplyEntry.Default(1) }).forEach { invalid ->
                assertFalse(preferences.setEntries(invalid))
                assertEquals(raw, stored.getString(QuickReplyPreferences.KEY, null))
                assertEquals(valid, preferences.entries.value)
            }
        }
        QuickReplyPreferences(context).use { assertEquals("  Synthetic text  ", (it.entries.value[0] as QuickReplyEntry.User).text) }
    }

    @Test fun commitFailureIsNotPublishedAndRestoresThePreviousMemoryView() {
        val baseline = QuickReplyPreferences.defaults.reversed()
        QuickReplyPreferences(context).use { assertTrue(it.setEntries(baseline)) }
        val raw = stored.getString(QuickReplyPreferences.KEY, null)
        QuickReplyPreferences(context).use { observer ->
            QuickReplyPreferences(failingQuickReplyContext(context)).use { preferences ->
                assertFalse(preferences.setEntries(listOf(QuickReplyEntry.User("Synthetic failed save"))))
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(baseline, preferences.entries.value)
                assertEquals(baseline, observer.entries.value)
                assertEquals(raw, stored.getString(QuickReplyPreferences.KEY, null))
            }
        }
        QuickReplyPreferences(context).use { assertEquals(baseline, it.entries.value) }
    }

    @Test fun liveOwnersObserveCommittedChangesAndClosedOwnerStopsObserving() {
        val first = QuickReplyPreferences(context)
        QuickReplyPreferences(context).use { second ->
            val changed = QuickReplyPreferences.defaults.reversed()
            assertTrue(second.setEntries(changed))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(changed, first.entries.value)
            // Queue a notification, then close before the posted refresh can run.
            assertTrue(second.setEntries(emptyList()))
            first.close()
            first.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(changed, first.entries.value)
            assertFalse(first.setEntries(QuickReplyPreferences.defaults))
        }
    }
}
