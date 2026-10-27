package org.openridesync.app.runtime

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class DashboardCustomizationContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun screenAwakeChoicesNeedAnExplicitSelectionAndExplainTheirScope() {
        val state = mutableStateOf(DashboardCustomizationState())
        val choices = mutableListOf<ScreenAwakeMode>()
        compose.setContent { MaterialTheme {
            DashboardCustomizationContent(state.value, { _, _ -> }, {}, {}, {}, {}, onScreenAwake = {
                choices += it; state.value = state.value.copy(screenAwake = it)
            })
        } }
        compose.runOnIdle { assertTrue(choices.isEmpty()) }
        compose.onNodeWithTag("screen_awake_OFF").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("screen_awake_ON").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("screen_awake_ONLY_CHARGING").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("screen_awake_OFF").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(ScreenAwakeMode.ON, ScreenAwakeMode.ONLY_CHARGING, ScreenAwakeMode.OFF), choices) }
        compose.onNodeWithText("僅在車輛已連線、App 位於前景時生效；RoadSync 與儀表模式皆適用。模擬預覽不會啟用。").assertExists()
    }

    @Test fun controlsDispatchOnlyExplicitChoicesAndUnavailableProvidersAreInformational() {
        val state = mutableStateOf(DashboardCustomizationState())
        var changes = 0
        var music = 0
        compose.setContent { MaterialTheme {
            DashboardCustomizationContent(state.value,
                onMove = { item, delta -> changes++; state.value = state.value.copy(order = moveFunction(state.value.order, item, delta)) },
                onSpeechMode = { changes++; state.value = state.value.copy(speechMode = it) },
                onAutoReturn = { changes++; state.value = state.value.copy(autoReturn = it) },
                onMusicApps = { music++ }, onBack = {})
        } }
        compose.runOnIdle { assertEquals(0, changes); assertEquals(0, music) }
        compose.onNodeWithTag("move_WEATHER_up").assertIsNotEnabled()
        compose.onNodeWithTag("move_WEATHER_down").performClick()
        compose.runOnIdle { assertEquals(MainMenuSelection.Item.NAVIGATION, state.value.order.first()) }
        compose.onNodeWithTag("weather_speech_SUMMARY").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("weather_speech_OFF").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("customization_auto_return").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(state.value.autoReturn); assertEquals(WeatherSpeechMode.OFF, state.value.speechMode) }
        compose.onNodeWithTag("customization_music_apps").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, music); assertEquals(4, changes) }
        compose.onNodeWithTag("function_NAVIGATION").assert(hasAnyDescendant(hasText("已提供 Maps 通知導航，需通知存取權與實際儀表模式；車端顯示與結束操作仍待實車驗證。")))
        compose.onNodeWithTag("function_CALL").assert(hasAnyDescendant(hasText("Android 14 以上的標準 CallStyle 通知可在實際儀表模式顯示來電與進行中通話。需通知存取權，不能代表所有電話或通訊 App；車端操作仍待實車驗證。")))
        compose.onNodeWithTag("function_MESSAGE").assert(hasAnyDescendant(hasText("在儀表模式瀏覽通知預覽；新訊息依下方處理設定提示或朗讀。把手回覆仍在開發中。")))
        compose.onAllNodesWithText("尚未實作提供者，儀表不會顯示此功能。").assertCountEquals(0)
    }

    @Test fun incomingMessageChoicesDispatchOnlyExplicitSelectionAndRenderSelectedMode() {
        val state = mutableStateOf(DashboardCustomizationState())
        val selected = mutableListOf<IncomingMessageMode>()
        compose.setContent { MaterialTheme {
            DashboardCustomizationContent(state.value, onMove = { _, _ -> }, onSpeechMode = {},
                onAutoReturn = {}, onMusicApps = {}, onBack = {}, onIncomingMessages = {
                    selected += it
                    state.value = state.value.copy(incomingMessages = it)
                })
        } }
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        compose.onNodeWithTag("incoming_messages_ASK_TO_LISTEN").performScrollTo().assertIsSelected()
        compose.onNodeWithText("只提示 App 與訊息標籤，確認後才朗讀正文。").assertExists()
        compose.onNodeWithTag("incoming_messages_AUTO_READ").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("incoming_messages_ASK_TO_LISTEN").assertIsNotSelected()
        compose.onNodeWithTag("incoming_messages_IGNORE").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("停止自動提示與朗讀，仍可手動瀏覽訊息。").assertExists()
        compose.onNodeWithTag("incoming_messages_ASK_TO_LISTEN").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(IncomingMessageMode.AUTO_READ, IncomingMessageMode.IGNORE,
            IncomingMessageMode.ASK_TO_LISTEN), selected) }
        compose.onNodeWithText("僅在實際連線且儀表為 SAB 模式、通知存取已授權時處理新訊息。鎖屏時僅使用 Android 提供的通知內容。").assertExists()
    }
}
