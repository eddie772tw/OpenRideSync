package org.openridesync.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openridesync.app.media.MediaAccess
import org.openridesync.app.media.MediaContent
import org.openridesync.app.media.MediaPageState
import org.openridesync.app.media.MediaSessionChoice
import org.openridesync.app.media.MediaState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rTW-w430dp-h932dp-mdpi")
class NotificationListenerStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun grantedCardSeparatesListenerConnectionAndKeepsBothSettingsRoutes() {
        val connected = mutableStateOf(false)
        var phoneSettings = 0
        var accessSettings = 0
        compose.setContent { MaterialTheme {
            AccessCard(AppAccess.MEDIA, granted = true, onRequest = {}, onSettings = { accessSettings++ },
                listenerConnected = connected.value, onPhoneSettings = { phoneSettings++ })
        } }
        compose.onNodeWithTag("permission_MEDIA_status").assertTextEquals("已開啟")
        compose.onNodeWithTag("permission_MEDIA_connection").assertTextEquals("通知服務尚未連線")
        compose.onNodeWithText("通知存取已授權，但通知服務尚未連線。訊息通知與通知來電目前無法可靠讀取；已可使用的媒體播放器仍可操作。")
            .assertExists()
        compose.onNodeWithTag("permission_MEDIA_phone_settings").performClick()
        compose.onNodeWithTag("permission_MEDIA_settings").performClick()
        compose.runOnIdle { assertEquals(1, phoneSettings); assertEquals(1, accessSettings); connected.value = true }
        compose.onNodeWithTag("permission_MEDIA_phone_settings").assertDoesNotExist()
        compose.onNodeWithTag("permission_MEDIA_connection").assertTextEquals("通知服務已連線")
        compose.onNodeWithTag("permission_MEDIA_status").assertTextEquals("已開啟")
    }

    @Test fun disconnectedListenerShowsRecoveryWhileMediaSessionsRemainAvailable() {
        var access = 0
        val state = MediaPageState(signedIn = true, observing = true,
            media = MediaState(access = MediaAccess.GRANTED,
                sessions = listOf(MediaSessionChoice("synthetic-player", "合成播放器", "synthetic.invalid"))))
        compose.setContent { MaterialTheme {
            MediaContent(state, {}, {}, { access++ }, {}, {}, {}, {}, {}, listenerDisconnected = true)
        } }
        compose.onNodeWithTag("media-session-synthetic-player").assertExists()
        compose.onNodeWithTag("media-listener-access").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, access) }
    }
}
