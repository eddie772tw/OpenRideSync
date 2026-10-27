package org.openridesync.app.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.runtime.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
// Explicit widths trigger Robolectric Dialog/TextField layout loops (robolectric/robolectric#8460).
// Real device coverage exercises the production editor at the actual phone/tablet widths.
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickReplySettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val stored get() = context.getSharedPreferences(QuickReplyPreferences.FILE, Context.MODE_PRIVATE)
    @Before fun reset() { stored.edit().clear().commit() }

    private fun rowAction(index: Int, action: String) {
        compose.onNodeWithTag("quick_reply_list").performScrollToIndex(index)
        compose.onNodeWithTag("quick_reply_${action}_$index").performClick()
    }

    private fun editFirst(text: String) {
        rowAction(0, "edit")
        compose.onNodeWithTag("quick_reply_editor").performTextReplacement(text)
        compose.onNodeWithTag("quick_reply_edit_confirm").performClick()
    }

    private fun waitForMessage(text: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun cancelEditAndLeavingScreenNeverPersistDraftOrReorder() {
        QuickReplyPreferences(context).use { preferences ->
            val visible = mutableStateOf(true)
            compose.setContent { MaterialTheme {
                if (visible.value) QuickReplySettingsScreen({ visible.value = false }, preferences)
            } }
            rowAction(0, "edit")
            compose.onNodeWithTag("quick_reply_editor").performTextReplacement("Synthetic cancelled edit")
            compose.onNodeWithTag("quick_reply_edit_cancel").performClick()
            compose.onNodeWithTag("quick_reply_text_0").assertTextEquals("I am driving.")
            editFirst("Synthetic unsaved draft")
            rowAction(0, "down")
            compose.onNodeWithTag("quick_reply_cancel").performClick()
            compose.onNodeWithTag("quick_reply_settings").assertDoesNotExist()
            assertEquals(QuickReplyPreferences.defaults, preferences.entries.value)
            assertFalse(stored.contains(QuickReplyPreferences.KEY))
        }
    }

    @Test fun explicitSavePersistsEditReorderAndIndividualDefaultChoice() {
        QuickReplyPreferences(context).use { preferences ->
            compose.setContent { MaterialTheme { QuickReplySettingsScreen({}, preferences) } }
            editFirst("Synthetic saved reply")
            rowAction(0, "down")
            compose.onNodeWithTag("quick_reply_save").performClick()
            waitForMessage("Saved on this phone.")
            assertEquals(QuickReplyEntry.Default(2), preferences.entries.value[0])
            assertEquals(QuickReplyEntry.User("Synthetic saved reply"), preferences.entries.value[1])
            rowAction(1, "restore")
            compose.onNodeWithTag("quick_reply_default_5").performClick()
            assertEquals(QuickReplyEntry.User("Synthetic saved reply"), preferences.entries.value[1])
            compose.onNodeWithTag("quick_reply_save").performClick()
            waitForMessage("Saved on this phone.")
            assertEquals(QuickReplyEntry.Default(5), preferences.entries.value[1])
        }
        QuickReplyPreferences(context).use { assertEquals(QuickReplyEntry.Default(5), it.entries.value[1]) }
    }

    @Test fun corruptedValueNeedsRestoreAndSaveAndRestoreAloneDoesNotWrite() {
        stored.edit().putString(QuickReplyPreferences.KEY, "broken synthetic setting").commit()
        QuickReplyPreferences(context).use { preferences ->
            compose.setContent { MaterialTheme { QuickReplySettingsScreen({}, preferences) } }
            compose.onNodeWithTag("quick_reply_corrupt").assertExists()
            compose.onNodeWithTag("quick_reply_save").assertIsNotEnabled()
            compose.onNodeWithTag("quick_reply_restore_all").performClick()
            assertEquals("broken synthetic setting", stored.getString(QuickReplyPreferences.KEY, null))
            compose.onNodeWithTag("quick_reply_save").performClick()
            waitForMessage("Saved on this phone.")
            assertFalse(preferences.invalidStorage.value)
            assertEquals(QuickReplyPreferences.defaults, preferences.entries.value)
        }
    }

    @Test fun failedSaveShowsFailureWithoutPublishingTheDraft() {
        QuickReplyPreferences(failingQuickReplyContext(context)).use { preferences ->
            compose.setContent { MaterialTheme { QuickReplySettingsScreen({}, preferences) } }
            editFirst("Synthetic failed reply")
            compose.onNodeWithTag("quick_reply_save").performClick()
            waitForMessage("Could not save. Your changes have not been saved. Try again.")
            assertEquals(QuickReplyPreferences.defaults, preferences.entries.value)
            assertFalse(stored.contains(QuickReplyPreferences.KEY))
            compose.onNodeWithText("Saved on this phone.").assertDoesNotExist()
        }
    }
}
