package org.openridesync.app.media

import android.media.session.PlaybackState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic UI behavior only; no settings activity, media command or session query is invoked. */
class MediaScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("media-content").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    @Test fun emptySessionsExplainPlaybackPrerequisiteAndSettingsRequireClick() {
        var access = 0
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(MediaState(access = MediaAccess.GRANTED), signedIn = true, observing = true),
                {}, {}, { access++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("目前沒有可用的媒體工作階段。請先開啟音樂 App 並播放，再回來重新整理。").assertExists()
        compose.runOnIdle { assertEquals(0, access) }
        scrollTo("media-settings").performClick()
        scrollTo("media-access").performClick()
        compose.runOnIdle { assertEquals(1, access) }
    }

    @Test fun capabilityControlsRequireClickAndDispatchDoesNotInventPlayback() {
        var play = 0
        val state = MediaPageState(MediaState(access = MediaAccess.GRANTED,
            sessions = listOf(MediaSessionChoice("synthetic", "測試播放器", "synthetic.invalid")),
            selectedId = "synthetic", title = "測試曲目", playback = MediaPlayback.PAUSED,
            actions = PlaybackState.ACTION_PLAY), signedIn = true, observing = true,
            result = DispatchResult.DISPATCHED)
        compose.setContent { MaterialTheme { MediaContent(state, {}, {}, {}, {}, { play++ }, {}, {}, {}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("media-play"))
        compose.onNodeWithTag("media-play").assertIsEnabled()
        compose.onNodeWithTag("media-pause").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, play) }
        compose.onNodeWithTag("media-play").performClick()
        compose.runOnIdle { assertEquals(1, play) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("播放狀態：已暫停"))
        compose.onNodeWithText("播放狀態：已暫停").assertIsDisplayed()
        val result = hasText("控制請求已送出，等待媒體 App 回報狀態。")
        // The result may not be composed yet after scrolling back to the player status.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(result)
        compose.onNode(result).assertIsDisplayed()
    }
    @Test fun selectedPlayerShowsControlsBeforeSecondarySettings() {
        val choice = MediaSessionChoice("selected", "測試播放器", "synthetic.invalid")
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(signedIn = true, observing = true,
                media = MediaState(access = MediaAccess.GRANTED, sessions = listOf(choice), selectedId = choice.id,
                    actions = PlaybackState.ACTION_PLAY, artist = "演出者", album = "專輯")), {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("media-now-playing").assertExists()
        compose.onNodeWithText("演出者 · 專輯").assertExists()
        compose.onNodeWithTag("media-play").assertIsDisplayed()
        compose.onNodeWithTag("ride-auto-return").assertDoesNotExist()
        compose.onNodeWithTag("media-session-selected").assertDoesNotExist()
        scrollTo("media-change-player").performClick()
        scrollTo("media-session-selected").assertExists()
    }

    @Test fun signedOutRecoveryAndBackAreExplicit() {
        var accounts = 0
        var back = 0
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(), { back++ }, {}, {}, {}, {}, {}, {}, {}, onAccount = { accounts++ })
        } }
        compose.runOnIdle { assertEquals(0, accounts); assertEquals(0, back) }
        compose.onNodeWithTag("media-account").performClick()
        compose.onNodeWithTag("media-back").performClick()
        compose.onNodeWithTag("media-play").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, accounts); assertEquals(1, back) }
    }

    @Test fun revokedAccessHidesOldTrackAndControlsAndNeedsExplicitExplanation() {
        var access = 0
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(signedIn = true, observing = true,
                media = MediaState(access = MediaAccess.REQUIRED, selectedId = "old", title = "舊曲目",
                    actions = PlaybackState.ACTION_PLAY)), {}, {}, { access++ }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("舊曲目").assertDoesNotExist()
        compose.onNodeWithTag("media-play").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, access) }
        scrollTo("media-access").performClick()
        compose.runOnIdle { assertEquals(1, access) }
    }

    @Test fun choosingSessionDoesNotInvokePlaybackOrFavoriteSelection() {
        var selected: String? = null
        var plays = 0
        var favorites = 0
        val choice = MediaSessionChoice("candidate", "測試播放器", "synthetic.invalid")
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(signedIn = true, observing = true,
                media = MediaState(access = MediaAccess.GRANTED, sessions = listOf(choice))),
                {}, {}, {}, { selected = it }, { plays++ }, {}, {}, {}, onFavorite = { favorites++ })
        } }
        scrollTo("media-session-candidate").performClick()
        compose.runOnIdle { assertEquals("candidate", selected); assertEquals(0, plays); assertEquals(0, favorites) }
        compose.onNodeWithTag("media-play").assertDoesNotExist()
    }

    @Test fun phoneRiskAndRecoveryRemainAvailableWithSettingsCollapsed() {
        var explanations = 0
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(signedIn = true, observing = true), {}, {}, {}, {}, {}, {}, {}, {},
                onPhonePermission = { explanations++ })
        } }
        compose.onNodeWithTag("ride-auto-return").assertDoesNotExist()
        scrollTo("media-phone-permission").performClick()
        compose.runOnIdle { assertEquals(1, explanations) }
    }

    @Test fun staleSelectionOffersRetryWithoutOldTransportControls() {
        var retries = 0
        compose.setContent { MaterialTheme {
            MediaContent(MediaPageState(signedIn = true, observing = true,
                media = MediaState(access = MediaAccess.GRANTED, selectedId = "old", actions = PlaybackState.ACTION_PLAY)),
                {}, { retries++ }, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("media-play").assertDoesNotExist()
        scrollTo("media-retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun liveRevocationAndStopRemoveControlsWithoutDispatch() {
        val choice = MediaSessionChoice("live", "測試播放器", "synthetic.invalid")
        val ready = MediaPageState(signedIn = true, observing = true,
            media = MediaState(access = MediaAccess.GRANTED, sessions = listOf(choice), selectedId = choice.id,
                title = "測試曲目", actions = PlaybackState.ACTION_PLAY))
        val state = mutableStateOf(ready)
        var plays = 0
        compose.setContent { MaterialTheme { MediaContent(state.value, {}, {}, {}, {}, { plays++ }, {}, {}, {}) } }
        compose.onNodeWithTag("media-play").assertExists()
        compose.runOnIdle { state.value = ready.copy(media = ready.media.copy(access = MediaAccess.REQUIRED)) }
        compose.onNodeWithTag("media-play").assertDoesNotExist()
        compose.onNodeWithText("測試曲目").assertDoesNotExist()
        compose.runOnIdle { state.value = ready }
        scrollTo("media-play").assertExists()
        compose.runOnIdle { state.value = ready.copy(observing = false) }
        compose.onNodeWithTag("media-play").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun disclosureAndRecompositionNeverInvokeFeatureActions() {
        val choice = MediaSessionChoice("live", "測試播放器", "synthetic.invalid")
        val state = mutableStateOf(MediaPageState(signedIn = true, observing = true, phoneStateGranted = true,
            rideConnected = true, rideSelection = choice,
            media = MediaState(access = MediaAccess.GRANTED, sessions = listOf(choice), selectedId = choice.id)))
        var actions = 0
        compose.setContent { MaterialTheme {
            MediaContent(state.value, {}, { actions++ }, { actions++ }, { actions++ }, { actions++ },
                { actions++ }, { actions++ }, { actions++ }, onRideMusic = { actions++ },
                onPhonePermission = { actions++ }, onAutoReturn = { actions++ }, onFavorite = { actions++ })
        } }
        scrollTo("media-change-player").performClick()
        scrollTo("media-change-player").performClick()
        scrollTo("media-settings").performClick()
        scrollTo("ride-auto-return").assertExists()
        scrollTo("media-settings").performClick()
        scrollTo("media-ride-details").performClick()
        scrollTo("media-ride-details").performClick()
        compose.runOnIdle { state.value = state.value.copy(media = state.value.media.copy(title = "更新曲目")) }
        scrollTo("media-now-playing").assertExists()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun favoritePageOffersAccountWithoutChangingPreference() {
        var accounts = 0
        var selections = 0
        compose.setContent { MaterialTheme {
            FavoriteMusicContent(FavoriteMusicState(), {}, {}, { selections++ }, { accounts++ })
        } }
        compose.onNodeWithTag("favorite-account").performClick()
        compose.runOnIdle { assertEquals(1, accounts); assertEquals(0, selections) }
    }

}
