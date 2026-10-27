package org.openridesync.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openridesync.app.navigation.MainTab

/** One navigation owner remains in MainActivity; this shell only emits explicit tab selections. */
@Composable
internal fun MainTabShell(routes: List<String>, onSelect: (String) -> Unit,
    navigationInsets: WindowInsets = WindowInsets.navigationBars, immersive: Boolean = false, content: @Composable () -> Unit) {
    if (immersive || !MainTab.visible(routes.lastOrNull() ?: "home")) { content(); return }
    val selected = MainTab.selected(routes)
    val largeText = LocalDensity.current.fontScale > 1.4f
    Column(Modifier.fillMaxSize().testTag("main_tab_shell")) {
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(navigationInsets.only(WindowInsetsSides.Bottom))) { content() }
        NavigationBar(modifier = Modifier.fillMaxWidth().heightIn(min = if (largeText) 120.dp else 80.dp),
            windowInsets = navigationInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) {
            MainTab.entries.forEach { tab ->
                NavigationBarItem(selected = tab == selected, onClick = { onSelect(tab.route) },
                    icon = { MainTabIcon(tab) }, alwaysShowLabel = true,
                    label = { Text(tab.label, modifier = if (largeText) Modifier.requiredWidth(56.dp) else Modifier,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2) },
                    modifier = Modifier.testTag("main_tab_${tab.route}"))
            }
        }
    }
}

/** Original geometric symbols; no external/OEM icon asset dependency. Text supplies accessibility. */
@Composable
private fun MainTabIcon(tab: MainTab) {
    val ink = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width; val h = size.height; val stroke = Stroke(1.8.dp.toPx())
        fun point(x: Float, y: Float) = Offset(w * x, h * y)
        when (tab) {
            MainTab.HOME -> {
                drawPath(Path().apply { moveTo(w*.12f,h*.46f); lineTo(w*.5f,h*.12f); lineTo(w*.88f,h*.46f)
                    moveTo(w*.22f,h*.39f); lineTo(w*.22f,h*.88f); lineTo(w*.78f,h*.88f); lineTo(w*.78f,h*.39f) },ink,style=stroke)
            }
            MainTab.TRIPS -> {
                drawPath(Path().apply { moveTo(w*.2f,h*.2f); lineTo(w*.75f,h*.4f); lineTo(w*.3f,h*.8f) },ink,style=stroke)
                listOf(point(.2f,.2f),point(.75f,.4f),point(.3f,.8f)).forEach { drawCircle(ink,w*.08f,it) }
            }
            MainTab.GARAGE -> {
                drawRoundRect(ink,point(.12f,.4f),Size(w*.76f,h*.35f),CornerRadius(w*.08f),style=stroke)
                drawPath(Path().apply { moveTo(w*.22f,h*.4f); lineTo(w*.32f,h*.18f); lineTo(w*.68f,h*.18f); lineTo(w*.78f,h*.4f) },ink,style=stroke)
                drawCircle(ink,w*.07f,point(.27f,.82f)); drawCircle(ink,w*.07f,point(.73f,.82f))
            }
            MainTab.CUSTOMIZE -> {
                listOf(.2f,.5f,.8f).forEach { drawLine(ink,point(it,.12f),point(it,.88f),stroke.width) }
                listOf(point(.2f,.3f),point(.5f,.7f),point(.8f,.45f)).forEach { drawCircle(ink,w*.09f,it) }
            }
            MainTab.SETTINGS -> {
                drawCircle(ink,w*.28f,center,style=stroke); drawCircle(ink,w*.07f,center)
                listOf(Offset(0f,-1f),Offset(0f,1f),Offset(-1f,0f),Offset(1f,0f)).forEach {
                    drawLine(ink,center+it*(w*.3f),center+it*(w*.45f),stroke.width)
                }
            }
        }
    }
}
