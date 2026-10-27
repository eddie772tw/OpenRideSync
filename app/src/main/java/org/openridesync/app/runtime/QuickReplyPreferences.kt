package org.openridesync.app.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.openridesync.app.R
import java.util.Collections

internal sealed interface QuickReplyEntry {
    data class Default(val order: Int) : QuickReplyEntry
    data class User(val text: String) : QuickReplyEntry
}

/** Local settings only. User text must never enter diagnostics, exports or research capture. */
internal class QuickReplyPreferences(context: Context) : AutoCloseable {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val current = MutableStateFlow<List<QuickReplyEntry>>(emptyList())
    val entries = current.asStateFlow()
    private val invalid = MutableStateFlow(false)
    val invalidStorage = invalid.asStateFlow()
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY || key == null) main.post {
            // Even synchronous commit notifications read the final saved or rolled-back value.
            synchronized(guard) { if (!closed) refresh() }
        }
    }

    init {
        synchronized(guard) {
            preferences.registerOnSharedPreferenceChangeListener(listener)
            refresh()
        }
    }

    private fun refresh() {
        val loaded = runCatching {
            val values = preferences.all
            if (KEY !in values) defaults else decode(values[KEY] as? String)
        }.getOrNull()
        invalid.value = loaded == null
        current.value = loaded ?: emptyList()
    }

    /** Call off main. A false result is never a successful save, including failed rollback. */
    fun setEntries(entries: List<QuickReplyEntry>): Boolean = synchronized(guard) {
        if (closed || !valid(entries)) return false
        val copy = entries.toList()
        val previous = runCatching { preferences.all }.getOrElse { return false }
        // Recovery requires the editor's explicit Restore all -> Save action; reads never repair data.
        if (KEY in previous && decode(previous[KEY] as? String) == null && copy != defaults) return false
        val encoded = encode(copy)
        if (encoded.length > MAX_STORED_CHARS) return false
        val saved = runCatching { preferences.edit().putString(KEY, encoded).commit() }.getOrDefault(false)
        if (saved) {
            invalid.value = false
            current.value = Collections.unmodifiableList(copy)
            true
        } else {
            // Android commit can change memory even when the disk write fails. Restore that view too.
            runCatching {
                val editor = preferences.edit()
                when (val value = previous[KEY]) {
                    null -> editor.remove(KEY)
                    is String -> editor.putString(KEY, value)
                    is Int -> editor.putInt(KEY, value)
                    is Long -> editor.putLong(KEY, value)
                    is Boolean -> editor.putBoolean(KEY, value)
                    is Float -> editor.putFloat(KEY, value)
                    is Set<*> -> editor.putStringSet(KEY, value.filterIsInstance<String>().toSet())
                }
                editor.commit()
            }
            false
        }
    }

    override fun close() = synchronized(guard) {
        if (!closed) {
            closed = true
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    companion object {
        internal const val FILE = "quick_reply_preferences"
        internal const val KEY = "entries_v1"
        val defaults: List<QuickReplyEntry> = Collections.unmodifiableList((1..5).map { QuickReplyEntry.Default(it) })
        // Native defensive limits, not an inferred OEM five-item maximum. Preserve valid order/count.
        private const val MAX_ENTRIES = 256
        private const val MAX_STORED_CHARS = 1_048_576
        // ponytail: serialize these small local saves; split locks only if real contention appears.
        private val guard = Any()

        private fun valid(entries: List<QuickReplyEntry>): Boolean = entries.size <= MAX_ENTRIES && entries.all {
            when (it) {
                is QuickReplyEntry.Default -> it.order in 1..5
                is QuickReplyEntry.User -> it.text.length <= 2000 && RideMessageReply.text(it.text) != null
            }
        }

        private fun decode(raw: String?): List<QuickReplyEntry>? = runCatching {
            require(raw != null && raw.length <= MAX_STORED_CHARS)
            val tokens = JSONTokener(raw)
            val root = tokens.nextValue() as? JSONObject ?: error("Invalid quick reply settings")
            require(tokens.nextClean() == '\u0000' && root.length() == 2 && root.opt("version") == 1)
            val array = root.getJSONArray("entries")
            require(array.length() <= MAX_ENTRIES)
            val result = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                require(item.length() == 2)
                when (item.opt("type")) {
                    "default" -> QuickReplyEntry.Default((item.opt("order") as? Int) ?: error("Invalid default"))
                    "user" -> QuickReplyEntry.User((item.opt("text") as? String) ?: error("Invalid text"))
                    else -> error("Invalid quick reply type")
                }
            }
            require(valid(result))
            Collections.unmodifiableList(result)
        }.getOrNull()

        private fun encode(entries: List<QuickReplyEntry>): String {
            val array = JSONArray()
            entries.forEach { entry -> array.put(when (entry) {
                is QuickReplyEntry.Default -> JSONObject().put("type", "default").put("order", entry.order)
                is QuickReplyEntry.User -> JSONObject().put("type", "user").put("text", entry.text)
            }) }
            return JSONObject().put("version", 1).put("entries", array).toString()
        }
    }
}

/** Defaults follow the supplied resource context; user edits retain their exact stored text. */
internal fun quickReplyTexts(context: Context, entries: List<QuickReplyEntry>): List<String> = entries.map {
    when (it) {
        is QuickReplyEntry.User -> it.text
        is QuickReplyEntry.Default -> context.getString(when (it.order) {
            1 -> R.string.quick_reply_default_1
            2 -> R.string.quick_reply_default_2
            3 -> R.string.quick_reply_default_3
            4 -> R.string.quick_reply_default_4
            5 -> R.string.quick_reply_default_5
            else -> error("Invalid quick reply default")
        })
    }
}
