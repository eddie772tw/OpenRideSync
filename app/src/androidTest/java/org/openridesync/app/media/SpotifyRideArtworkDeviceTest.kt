package org.openridesync.app.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.openridesync.app.runtime.MainMenuSelection
import org.openridesync.app.runtime.RidePageStage
import org.openridesync.app.runtime.RidePageState
import org.openridesync.app.runtime.RideRidingMenu
import org.openridesync.app.ui.theme.OpenRideSyncTheme

/** Opt-in read-only integration with a user-prepared Spotify session. No playback or vehicle command.
 * Screenshots contain real track metadata/artwork and are saved only to the private app cache. */
class SpotifyRideArtworkDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentSpotifyArtworkRendersInTheSharedMenuAcrossFocusChanges() {
        assumeTrue("Run only during an explicitly authorized Spotify test",
            InstrumentationRegistry.getArguments().getString("observeSpotify") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var backend: NativeMedia? = null
        var observed = MediaState()
        try {
            compose.runOnIdle {
                backend = NativeMedia(context).also { it.start() }
                assertTrue("One Spotify session must already be available", backend!!.selectOnlyPackage("com.spotify.music"))
            }
            compose.waitUntil(10_000) {
                backend!!.state.value.let { it.selectedId != null && it.artwork != null && !it.title.isNullOrBlank() }
            }
            compose.runOnIdle { observed = backend!!.state.value }
            val page = mutableStateOf(RidePageState(RidePageStage.MENU,
                menuItems = listOf(MainMenuSelection.Item.MUSIC, MainMenuSelection.Item.VOLUME),
                menuSelected = MainMenuSelection.Item.NAVIGATION, volumePercentage = 60))
            compose.setContent { OpenRideSyncTheme { Column {
                Text("測試：真實 Spotify 媒體／合成選單與音量，非實車連線")
                RideRidingMenu(page.value, observed, {})
            } } }
            fun save(name: String) {
                context.cacheDir.resolve("test-spotify-ride-$name.png").outputStream().use {
                    check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            compose.onNodeWithTag("ride_music_artwork", useUnmergedTree = true).assertExists()
            save("unfocused")
            compose.runOnIdle { page.value = page.value.copy(menuSelected = MainMenuSelection.Item.MUSIC) }
            compose.onNodeWithTag("ride_function_MUSIC").assertIsSelected()
            compose.onNodeWithText(observed.title!!).assertExists()
            save("focused")
            compose.runOnIdle { page.value = page.value.copy(menuSelected = MainMenuSelection.Item.VOLUME) }
            compose.onNodeWithTag("ride_function_VOLUME").assertIsSelected()
            save("volume")
            context.cacheDir.resolve("test-spotify-ride.json").writeText(JSONObject().apply {
                put("sourcePackage", "com.spotify.music")
                put("selected", observed.selectedId != null)
                put("titlePresent", !observed.title.isNullOrBlank())
                put("artistPresent", !observed.artist.isNullOrBlank())
                put("albumPresent", !observed.album.isNullOrBlank())
                put("artworkWidth", observed.artwork!!.width)
                put("artworkHeight", observed.artwork!!.height)
                put("playback", observed.playback.name)
                put("playbackCommands", 0)
                put("vehicleConnection", false)
                put("volumeFixture", 60)
            }.toString())
        } finally { compose.runOnIdle { backend?.close() } }
    }
}
