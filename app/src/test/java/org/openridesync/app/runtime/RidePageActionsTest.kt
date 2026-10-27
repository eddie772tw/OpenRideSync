package org.openridesync.app.runtime

import android.app.Application
import android.media.session.PlaybackState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.calls.*
import org.openridesync.app.media.*
import org.openridesync.app.navigation.NavigationObservation
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only production Compose content and synthetic callbacks; never a controller, service or real action. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class RidePageActionsTest {
    @get:Rule val compose = createComposeRule()
    private val music = MediaState(MediaAccess.GRANTED,
        sessions = listOf(MediaSessionChoice("synthetic-player", "合成播放器", "synthetic.invalid")),
        selectedId = "synthetic-player", title = "合成曲目", playback = MediaPlayback.PLAYING,
        actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT)
    private val navigation = NavigationObservation(canCancel = true)
    private val ongoing = CallInboxState(CallAccess.READY, listOf(CallItem("synthetic-call", "合成通話來源",
        "synthetic.invalid", "合成通話對象", CallKind.ONGOING, setOf(CallAction.HANG_UP), false)))

    @Test fun musicOnlyDispatchesToggleAndNextWhenTheUserClicks() {
        val requests = mutableListOf<Function>()
        compose.setContent { MaterialTheme {
            RidePageActions(RidePageState(RidePageStage.MUSIC), music, NavigationObservation(), CallInboxState(),
                { requests += it })
        } }
        compose.onNodeWithTag("ride_action_BACK").assertIsEnabled().assertTextEquals("播放／暫停")
        compose.onNodeWithTag("ride_action_SELECT").assertIsEnabled().assertTextEquals("下一首")
        compose.onNodeWithText("上一首").assertDoesNotExist()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("ride_action_BACK").performClick()
        compose.onNodeWithTag("ride_action_SELECT").performClick()
        compose.runOnIdle { assertEquals(listOf(Function.BACK, Function.SELECT), requests) }
    }

    @Test fun huAndUnknownModesDisableTheSharedBusinessActionsInRideContent() {
        val ride = mutableStateOf(RideState(RideStage.CONNECTED, mode = Function.MODE_SAB))
        val requests = mutableListOf<Function>()
        compose.setContent { OpenRideSyncTheme {
            RideContent(ride.value, false, music, RidePageState(RidePageStage.MUSIC), {}, {}, {}, {}, {}, {},
                onAction = { requests += it })
        } }
        compose.onNodeWithTag("ride_action_BACK").performScrollTo().assertIsEnabled()
        for (mode in listOf(Function.MODE_HU, null)) {
            compose.runOnIdle { ride.value = ride.value.copy(mode = mode) }
            listOf(Function.BACK, Function.SELECT, Function.MENU).forEach {
                compose.onNodeWithTag("ride_action_${it.name}").assertIsNotEnabled()
            }
        }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
    }

    @Test fun volumeButtonsUseTheSameNextAndPreviousBusinessActionsAsTheHandlebar() {
        val requests = mutableListOf<Function>()
        compose.setContent { MaterialTheme {
            RidePageActions(RidePageState(RidePageStage.VOLUME, volumePercentage = 60), null,
                NavigationObservation(), CallInboxState(), { requests += it })
        } }
        compose.onNodeWithTag("ride_action_NEXT").assertTextEquals("音量 −").performClick()
        compose.onNodeWithTag("ride_action_PREVIOUS").assertTextEquals("音量 ＋").performClick()
        compose.runOnIdle { assertEquals(listOf(Function.NEXT, Function.PREVIOUS), requests) }
    }

    @Test fun quickReplySelectionIsExplicitAndEmptyOrInvalidTemplatesCannotSend() {
        val page = mutableStateOf(RidePageState(RidePageStage.MESSAGE_QUICK_LIST,
            replyChoices = listOf("Synthetic template")))
        val requests = mutableListOf<Function>()
        compose.setContent { MaterialTheme {
            RidePageActions(page.value, null, NavigationObservation(), CallInboxState(), { requests += it })
        } }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("ride_action_NEXT").assertIsNotEnabled()
        compose.onNodeWithTag("ride_action_PREVIOUS").assertIsNotEnabled()
        compose.onNodeWithTag("ride_action_SELECT").assertTextEquals("傳送選取回覆").performClick()
        compose.runOnIdle { assertEquals(listOf(Function.SELECT), requests) }
        for (templates in listOf(emptyList(), listOf("a\u0000b"))) {
            compose.runOnIdle { page.value = page.value.copy(replyChoices = templates) }
            compose.onNodeWithTag("ride_action_SELECT").assertIsNotEnabled()
        }
        compose.onNodeWithTag("ride_action_BACK").assertTextEquals("返回回覆方式")
    }

    @Test fun interruptedWeatherUsesTheOwnersRetryFlagInsteadOfGuessingFromAnIdleWeatherReading() {
        val page = mutableStateOf(RidePageState(RidePageStage.WEATHER, weatherCanRetry = true,
            weather = org.openridesync.app.weather.RideWeatherState()))
        val requests = mutableListOf<Function>()
        compose.setContent { MaterialTheme {
            RidePageActions(page.value, null, NavigationObservation(), CallInboxState(), { requests += it })
        } }
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithTag("ride_action_SELECT").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Function.SELECT), requests); page.value = page.value.copy(weatherCanRetry = false) }
        compose.onNodeWithTag("ride_action_SELECT").assertDoesNotExist()
    }

    @Test fun navigationAndCallCancellationHaveExplicitConfirmAndKeepLabels() {
        val page = mutableStateOf(RidePageState(RidePageStage.NAVIGATION_CANCEL))
        val requests = mutableListOf<Function>()
        compose.setContent { MaterialTheme {
            RidePageActions(page.value, null, navigation, ongoing, { requests += it })
        } }
        for ((stage, labels) in listOf(
            RidePageStage.NAVIGATION_CANCEL to ("確認結束導航" to "繼續導航"),
            RidePageStage.CALL_CANCEL to ("確認結束通話" to "繼續通話"))) {
            compose.runOnIdle { page.value = RidePageState(stage); requests.clear() }
            compose.onNodeWithTag("ride_action_SELECT").assertIsEnabled().assertTextEquals(labels.first)
            compose.onNodeWithTag("ride_action_BACK").assertIsEnabled().assertTextEquals(labels.second)
            compose.runOnIdle { assertTrue(requests.isEmpty()) }
            compose.onNodeWithTag("ride_action_SELECT").performClick()
            compose.onNodeWithTag("ride_action_BACK").performClick()
            compose.runOnIdle { assertEquals(listOf(Function.SELECT, Function.BACK), requests) }
        }
    }

    @Test fun previewPageActionsOnlyChangeSyntheticStateAndNeverInvokeRealHostActions() {
        var externalMedia = 0
        var externalWeather = 0
        compose.setContent { OpenRideSyncTheme {
            RidePreviewScreen({}, { externalMedia++ }, { externalWeather++ })
        } }
        compose.onNodeWithTag("ride_function_MUSIC").performScrollTo().performClick()
        compose.onNodeWithTag("ride_action_BACK").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ride_action_SELECT").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("ride_action_MENU").performScrollTo().performClick()
        compose.onNodeWithTag("ride_function_menu").assertExists()
        compose.onNodeWithTag("ride_preview_banner").assertExists()
        compose.onNodeWithTag("preview_controls_toggle").performClick()
        compose.onNodeWithTag("preview_local_feedback").performScrollTo().assertExists()
        compose.onNodeWithTag("preview_controls_close").performClick()
        compose.runOnIdle { assertEquals(0, externalMedia); assertEquals(0, externalWeather) }
    }
}
