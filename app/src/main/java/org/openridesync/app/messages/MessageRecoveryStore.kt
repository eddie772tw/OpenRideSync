package org.openridesync.app.messages

import android.content.Context
import java.security.MessageDigest

/** Pending identities only. No notification key, sender, body, action or account UID is saved. */
internal class MessageRecoveryStore(context: Context, uid: String) {
    private val preferences = context.getSharedPreferences("message-recovery", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Null makes automatic recovery unavailable rather than inventing an empty pending list. */
    fun load(): Set<String>? = try {
        val value = preferences.all[key]
        when {
            value == null -> emptySet()
            value !is Set<*> || value.size > LIMIT || value.any { it !is String || !HASH.matches(it) } -> null
            else -> value.filterIsInstance<String>().toSet()
        }
    } catch (_: RuntimeException) { null }

    fun save(pending: Set<String>): Boolean {
        require(pending.size <= LIMIT && pending.all(HASH::matches))
        // Commit before presenting: a process death must not lose an already accepted pending item.
        return try { preferences.edit().putStringSet(key, pending.toSet()).commit() }
        catch (_: RuntimeException) { false }
    }

    companion object {
        const val LIMIT = 50 // Same bound as the live notification inbox.
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
