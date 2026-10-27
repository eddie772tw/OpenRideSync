package org.openridesync.app.ui

import android.app.Application
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.R
import org.openridesync.app.runtime.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Synthetic provider only; composition never creates a speech recognizer or asks for a grant. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS-w430dp-h932dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecognitionLanguageScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun languageNamesFollowComposeConfigurationAndCapabilitiesRequireAnExplicitQuery() {
        val context: Application = RuntimeEnvironment.getApplication()
        var requests = 0
        val languages = RecognitionLanguages(context, { "synthetic.recognizer/SyntheticService@1" }, { _, _, _ ->
            requests++
            RecognitionProviderLanguages(RecognitionLanguageQuerySource.RECOGNITION_SUPPORT,
                installed = listOf("en-US"), supported = listOf("cmn-Hant-TW"))
        })
        val configuration = mutableStateOf(Configuration(context.resources.configuration).apply { setLocale(Locale.US) })
        try {
            check(languages.select("en-US"))
            val selection = languages.selection.value
            compose.setContent { MaterialTheme {
                CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                    RecognitionLanguageScreen({}, languages)
                }
            } }
            compose.onNodeWithTag("recognition_language_en-US").assertTextContains("English (United States)")
            compose.runOnIdle {
                assertEquals(0, requests)
                configuration.value = Configuration(configuration.value).apply { setLocale(Locale.TAIWAN) }
            }
            compose.onNodeWithTag("recognition_language_en-US").assertTextContains("英文 (美國)")
            compose.runOnIdle { assertEquals(selection, languages.selection.value); assertEquals(0, requests) }
            compose.onNodeWithTag("recognition_language_query").performScrollTo().performClick()
            compose.waitUntil { languages.support.value.stage == RecognitionLanguageQueryStage.COMPLETE }
            compose.onNodeWithText(context.getString(R.string.recognition_language_installed)).assertExists()
            compose.onNodeWithText(context.getString(R.string.recognition_language_supported)).assertExists()
            compose.runOnIdle { assertEquals(1, requests); assertEquals(selection, languages.selection.value) }
        } finally { languages.close() }
    }
}
