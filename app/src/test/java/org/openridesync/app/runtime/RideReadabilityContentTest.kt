package org.openridesync.app.runtime

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.ble.Gen1Input
import org.openridesync.app.media.FavoriteStartStatus
import org.openridesync.app.ui.theme.OpenRideSyncTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic rendering and explicit callbacks only; no service, media or vehicle operation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w430dp-h932dp-mdpi")
class RideReadabilityContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reconnectFailuresKeepManualConnectionAvailableWithoutPreciseLocation() {
        listOf("RECONNECT_LOCATION_REQUIRED", "RECONNECT_STOPPED", "RECONNECT_WINDOW_EXPIRED", "RECONNECT_LIMIT").forEach {
            assertTrue(rideFailureMessage(it).contains("車庫手動連線"))
            assertFalse(rideFailureMessage(it).contains("精確位置"))
        }
        assertTrue(rideFailureMessage("RECONNECT_LOCATION_REQUIRED").contains("概略位置"))
    }

    @Test fun reconnectingHidesRetainedVehicleActionsAndKeepsCancelAndPermissionsReachable() {
        val state = mutableStateOf(RideState(RideStage.RECONNECTING,
            mode = Gen1Input.Function.MODE_HU, favoriteStart = FavoriteStartStatus.STARTING))
        var cancellations = 0
        var permissions = 0
        compose.setContent { OpenRideSyncTheme {
            RideContent(state.value, false, null, RidePageState(RidePageStage.MUSIC, receiptStatus = 1),
                {}, {}, {}, {}, { cancellations++ }, {}, onPermissions = { permissions++ }, onRetryFavorite = {})
        } }
        compose.onNodeWithTag("ride_status").assertTextEquals("正在重新連接車輛…")
        compose.onNodeWithTag("ride_connecting_progress").assertExists()
        compose.onNodeWithTag("ride_display_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_music_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.onNodeWithText("車端已接受畫面指令，實際顯示請以儀表為準。").assertDoesNotExist()
        compose.onNodeWithTag("ride_disconnect").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, cancellations)
            state.value = RideState(RideStage.FAILED, errorCode = "RECONNECT_LOCATION_REQUIRED")
        }
        compose.onNodeWithText(rideFailureMessage("RECONNECT_LOCATION_REQUIRED")).assertExists()
        compose.onNodeWithTag("ride_permissions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, permissions) }
    }

    @Test fun menuOmitsEmptyFeaturesAndMusicPageRetainsExplicitRetryAndFailures() {
        val page = mutableStateOf(RidePageState(RidePageStage.MENU, receiptStatus = 1))
        var retries = 0
        var opens = 0
        compose.setContent { OpenRideSyncTheme {
            RideContent(RideState(RideStage.CONNECTED, mode = Gen1Input.Function.MODE_SAB,
                favoriteStart = FavoriteStartStatus.NO_SELECTION), false, null,
                page.value, {}, {}, { opens++ }, {}, {}, {}, onRetryFavorite = { retries++ })
        } }
        compose.onNodeWithTag("ride_current_function").assertTextEquals("主選單")
        compose.onNodeWithTag("ride_calls_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_navigation_state").assertDoesNotExist()
        compose.onNodeWithTag("ride_music_state").assertDoesNotExist()
        compose.onNodeWithText("尚未選擇播放器").assertDoesNotExist()
        compose.onNodeWithTag("ride_retry_favorite").assertDoesNotExist()
        compose.onNodeWithText("車端已接受畫面指令，實際顯示請以儀表為準。").assertDoesNotExist()
        compose.onNodeWithTag("ride_details_toggle").performScrollTo().performClick()
        compose.onNodeWithText("車端已接受畫面指令，實際顯示請以儀表為準。").assertExists()
        compose.onNodeWithTag("ride_media").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithTag("ride_details_toggle").performClick()
        compose.runOnIdle {
            assertEquals(0, retries)
            page.value = RidePageState(RidePageStage.MUSIC, receiptStatus = 11,
                messageRecoveryAvailable = false)
        }
        compose.onNodeWithTag("ride_current_function").performScrollTo().assertTextEquals("音樂")
        compose.onNodeWithText("車端未接受這次畫面指令。").assertExists()
        compose.onNodeWithTag("ride_message_recovery_unavailable").assertExists()
        compose.onNodeWithTag("ride_retry_favorite").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
