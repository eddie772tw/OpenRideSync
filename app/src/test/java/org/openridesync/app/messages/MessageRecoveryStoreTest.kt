package org.openridesync.app.messages

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MessageRecoveryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("message-recovery", 0).edit().clear().commit() }

    @Test fun pendingHashesRestoreOnlyForTheirAccountAndClearWithoutPersistingContent() {
        val first = MessageRecoveryStore(context, "synthetic-account-one")
        val second = MessageRecoveryStore(context, "synthetic-account-two")
        val pending = setOf("a".repeat(64), "b".repeat(64))
        assertTrue(first.save(pending))
        assertEquals(pending, MessageRecoveryStore(context, "synthetic-account-one").load())
        assertTrue(second.load()!!.isEmpty())
        val values = context.getSharedPreferences("message-recovery", 0).all
        assertTrue(values.keys.all { it.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(listOf(pending), values.values.toList())
        assertTrue(first.save(emptySet()))
        assertTrue(first.load()!!.isEmpty())
    }

    @Test fun malformedOrOversizedSavedPendingIsUnavailableAndInvalidWritesAreRejected() {
        val store = MessageRecoveryStore(context, "synthetic-account-one")
        store.save(setOf("a".repeat(64)))
        val preferences = context.getSharedPreferences("message-recovery", 0)
        val key = preferences.all.keys.single()
        preferences.edit().putBoolean(key, true).commit()
        assertNull(store.load())
        preferences.edit().putStringSet(key, setOf("not-a-hash")).commit()
        assertNull(store.load())
        val tooMany = (0..MessageRecoveryStore.LIMIT).map { "%064x".format(it) }.toSet()
        preferences.edit().putStringSet(key, tooMany).commit()
        assertNull(store.load())
        try { store.save(tooMany); fail("Oversized pending must be rejected") } catch (_: IllegalArgumentException) { }
        try { store.save(setOf("raw notification body")); fail("Raw content must be rejected") } catch (_: IllegalArgumentException) { }
    }
}
