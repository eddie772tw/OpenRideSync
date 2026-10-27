package org.openridesync.app.runtime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openridesync.app.media.MediaState
import org.openridesync.app.ui.OemRideAsset
import org.openridesync.app.ui.RideIcon
import org.openridesync.app.weather.WeatherKind

internal val RideBackground = Color(0xff202124)
internal val RideColors = darkColorScheme(background = RideBackground, surface = RideBackground,
    surfaceVariant = Color(0xff303134), primary = Color(0xffa8dab5), onPrimary = Color(0xff123721),
    onSurface = Color.White, onBackground = Color.White)

internal fun rideFunctionLabel(item: MainMenuSelection.Item): String = when (item) {
    MainMenuSelection.Item.WEATHER -> "天氣匯報"
    MainMenuSelection.Item.NAVIGATION -> "導航"
    MainMenuSelection.Item.CALL -> "通話"
    MainMenuSelection.Item.MESSAGE -> "訊息"
    MainMenuSelection.Item.MUSIC -> "音樂"
    MainMenuSelection.Item.VOLUME -> "音量"
}

/** The phone menu follows the live page owner's provider order and focus, never a second selector. */
@Composable
internal fun RideRidingMenu(page: RidePageState, media: MediaState?, onFunction: ((MainMenuSelection.Item) -> Unit)?) {
    val artworkTextShadow = with(LocalDensity.current) { Shadow(Color.Black, Offset(0f, 1.dp.toPx()), 3.dp.toPx()) }
    Column(Modifier.fillMaxWidth().testTag("ride_function_menu"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // OEM MainFragmentViewModel excludes MainWeatherPageItem; weather lives in the top bar.
        page.menuItems.filter { it != MainMenuSelection.Item.WEATHER }.forEach { item ->
            val focused = page.menuSelected == item
            val intoView = remember { BringIntoViewRequester() }
            LaunchedEffect(focused) { if (focused) intoView.bringIntoView() }
            val artwork = media?.artwork?.takeIf { item == MainMenuSelection.Item.MUSIC && !it.isRecycled }
            val shape = RoundedCornerShape(8.dp)
            Surface(onClick = { onFunction?.invoke(item) }, enabled = onFunction != null,
                color = Color(0xff313235), contentColor = Color.White, shape = shape,
                border = if (focused) BorderStroke(2.dp,
                    Brush.verticalGradient(listOf(Color(0xff00ffbc), Color(0xff00ff5e)))) else null,
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(intoView)
                    .testTag("ride_function_${item.name}").semantics { selected = focused }) {
                Box {
                    if (artwork != null) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.heightIn(max = 240.dp).fillMaxHeight().aspectRatio(1f).testTag("ride_music_artwork")) {
                            Image(artwork.asImageBitmap(), contentDescription = null,
                                modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                                alignment = Alignment.CenterEnd)
                            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                                0f to Color(0xff313235), .5f to Color.Transparent, 1f to Color.Transparent)))
                        }
                    }
                    Row(Modifier.heightIn(min = if (focused && item == MainMenuSelection.Item.MUSIC) 240.dp else 120.dp)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        RideFunctionIcon(item)
                        Column(Modifier.weight(1f).padding(end = if (artwork != null) 56.dp else 0.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(rideFunctionLabel(item), style = MaterialTheme.typography.headlineMedium.copy(
                                shadow = artworkTextShadow.takeIf { artwork != null }),
                                fontWeight = FontWeight.Medium)
                            if (focused && item == MainMenuSelection.Item.MUSIC) {
                                media?.title?.takeIf(String::isNotBlank)?.let {
                                    Text(it, Modifier.testTag("ride_music_title"), style = MaterialTheme.typography.titleLarge.copy(shadow = artworkTextShadow),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                media?.album?.takeIf(String::isNotBlank)?.let {
                                    Text("專輯：$it", Modifier.testTag("ride_music_album"), style = MaterialTheme.typography.bodyLarge.copy(shadow = artworkTextShadow),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                media?.artist?.takeIf(String::isNotBlank)?.let {
                                    Text("演出者：$it", Modifier.testTag("ride_music_artist"), style = MaterialTheme.typography.bodyLarge.copy(shadow = artworkTextShadow),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (focused && item == MainMenuSelection.Item.VOLUME) page.volumePercentage?.takeIf { it in 0..100 }?.let {
                                Text("$it%", Modifier.testTag("ride_volume_percentage"), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RideFunctionIcon(item: MainMenuSelection.Item, modifier: Modifier = Modifier) {
    if (item == MainMenuSelection.Item.WEATHER) Box(modifier.size(56.dp), contentAlignment = Alignment.Center) {
        OemRideWeatherIcon(WeatherKind.BROKEN_CLOUDS)
    } else RideIcon(when (item) {
        MainMenuSelection.Item.NAVIGATION -> OemRideAsset.NAVIGATION
        MainMenuSelection.Item.CALL -> OemRideAsset.CALL
        MainMenuSelection.Item.MESSAGE -> OemRideAsset.MESSAGE
        MainMenuSelection.Item.MUSIC -> OemRideAsset.MUSIC
        else -> OemRideAsset.VOLUME
    }, modifier)
}
