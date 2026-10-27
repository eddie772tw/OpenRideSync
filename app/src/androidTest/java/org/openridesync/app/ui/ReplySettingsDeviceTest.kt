package org.openridesync.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.R
import org.openridesync.app.runtime.QuickReplyEntry
import org.openridesync.app.runtime.QuickReplyPreferences
import org.openridesync.app.runtime.quickReplyTexts
import java.io.File
import java.util.UUID

/** Real Compose/preferences with synthetic text and UUID-isolated files. No message/ride owner,
 * notification, account, microphone, permission request, sender or Honda client is constructed. */
@RunWith(AndroidJUnit4::class)
class ReplySettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    private class IsolatedContext(base: Context, val prefix: String) : ContextWrapper(base) {
        private val files = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = "$prefix-$name"
            synchronized(files) { files.add(isolatedName) }
            return baseContext.getSharedPreferences(isolatedName, Context.MODE_PRIVATE)
        }
        fun cleanup() = synchronized(files) {
            files.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }

    private fun rowAction(index: Int, action: String) {
        compose.onNodeWithTag("quick_reply_list").performScrollToIndex(index)
        compose.onNodeWithTag("quick_reply_${action}_$index").performClick()
    }

    private fun enterSynthetic(text: String) {
        rowAction(0, "edit")
        compose.onNodeWithTag("quick_reply_editor").performTextReplacement(text)
    }

    private fun save(context: Context) {
        compose.onNodeWithTag("quick_reply_save").performClick()
        val saved = context.getString(R.string.quick_reply_saved)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(saved).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_reply_save").assertIsEnabled()
    }

    private fun capture(node: SemanticsNodeInteraction, output: File, suffix: String) {
        compose.waitForIdle()
        File(output, "synthetic-quick-reply-settings-$suffix.png").outputStream().use {
            check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun syntheticEditorCancelSaveReopenAndExplicitRestoreUseOnlyIsolatedPreferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = IsolatedContext(instrumentation.targetContext, "synthetic-quick-reply-${UUID.randomUUID()}")
        assertSame(context, context.applicationContext)
        val stored = context.getSharedPreferences(QuickReplyPreferences.FILE, Context.MODE_PRIVATE)
        val output = InstrumentationRegistry.getArguments().getString("uiGalleryOutputDir")?.takeIf { it.isNotBlank() }
            ?.let(::File) ?: File(context.cacheDir, context.prefix)
        check(output.isDirectory || output.mkdirs())
        val owner = mutableStateOf<QuickReplyPreferences?>(null)
        var active: QuickReplyPreferences? = null
        fun reopen(): QuickReplyPreferences {
            compose.runOnIdle { owner.value = null }
            compose.waitForIdle()
            active?.close()
            val next = QuickReplyPreferences(context)
            active = next
            compose.runOnIdle { owner.value = next }
            compose.waitForIdle()
            return next
        }
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContext provides context) {
                    owner.value?.let { preferences ->
                        key(preferences) { QuickReplySettingsScreen({ owner.value = null }, preferences) }
                    }
                }
            }
        }
        try {
            var preferences = reopen()
            val defaults = QuickReplyPreferences.defaults
            assertEquals(defaults, preferences.entries.value)
            assertFalse(stored.contains(QuickReplyPreferences.KEY))

            enterSynthetic("Synthetic quick reply settings cancelled")
            capture(compose.onNode(isDialog()), output, "editor")
            compose.onNodeWithTag("quick_reply_edit_cancel").performClick()
            compose.onNodeWithTag("quick_reply_text_0")
                .assertTextEquals(quickReplyTexts(context, defaults).first())
            assertFalse(stored.contains(QuickReplyPreferences.KEY))

            enterSynthetic("Synthetic quick reply settings unsaved")
            compose.onNodeWithTag("quick_reply_edit_confirm").performClick()
            rowAction(0, "down")
            compose.onNodeWithTag("quick_reply_cancel").performClick()
            compose.onNodeWithTag("quick_reply_settings").assertDoesNotExist()
            assertFalse(stored.contains(QuickReplyPreferences.KEY))
            preferences = reopen()
            assertEquals(defaults, preferences.entries.value)

            val synthetic = "Synthetic quick reply settings saved"
            enterSynthetic(synthetic)
            compose.onNodeWithTag("quick_reply_edit_confirm").performClick()
            rowAction(0, "down")
            assertEquals(defaults, preferences.entries.value)
            save(context)
            val expected = listOf(QuickReplyEntry.Default(2), QuickReplyEntry.User(synthetic),
                QuickReplyEntry.Default(3), QuickReplyEntry.Default(4), QuickReplyEntry.Default(5))
            assertEquals(expected, preferences.entries.value)
            compose.onNodeWithTag("quick_reply_list").performScrollToIndex(1)
            capture(compose.onNodeWithTag("quick_reply_settings"), output, "saved")

            preferences = reopen()
            assertEquals(expected, preferences.entries.value)
            compose.onNodeWithTag("quick_reply_list").performScrollToIndex(1)
            compose.onNodeWithTag("quick_reply_text_1").assertTextEquals(synthetic)
            compose.onNodeWithTag("quick_reply_restore_all").performClick()
            assertEquals(expected, preferences.entries.value) // Restore is still only a draft.
            save(context)
            assertEquals(defaults, preferences.entries.value)
            preferences = reopen()
            assertEquals(defaults, preferences.entries.value)
            assertFalse(preferences.invalidStorage.value)
        } finally {
            compose.runOnIdle { owner.value = null }
            compose.waitForIdle()
            active?.close()
            context.cleanup() // Deletes only names generated by this UUID wrapper.
        }
    }
}
