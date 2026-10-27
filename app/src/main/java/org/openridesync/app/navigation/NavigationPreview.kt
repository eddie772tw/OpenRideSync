package org.openridesync.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.openridesync.app.ble.Gen1Projection

/** Synthetic projection inspection only; no location, map app, guidance, speech or transport owner. */
@Composable
internal fun NavigationPreview(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var maneuver by remember { mutableStateOf(NavigationProjection.Maneuver.RIGHT) }
    var missing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val page = NavigationProjection.active(maneuver.takeUnless { missing }, "300 m", "導航中（合成）")
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        .testTag("navigation_preview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("navigation_preview_back")) { Text("返回儀表預覽") }
        Text("導航轉向資料預覽", style = MaterialTheme.typography.titleLarge)
        Text("全部資料均為合成樣本。此工具檢查導航畫面編碼，不是儀表像素還原；不取得位置、開啟地圖、播放語音或連接車輛。")
        Box {
            OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag("navigation_preview_choose")) {
                Text("轉向樣本：${maneuver.name}")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                NavigationProjection.Maneuver.entries.forEach { value ->
                    DropdownMenuItem(text = { Text(value.name) }, onClick = { maneuver = value; menu = false },
                        modifier = Modifier.testTag("navigation_preview_${value.name}"))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("模擬缺少可信轉向", Modifier.weight(1f))
            Switch(checked = missing, onCheckedChange = { missing = it }, modifier = Modifier.testTag("navigation_preview_missing"))
        }
        if (page == null) Text("未產生導航畫面：缺少可信轉向。", Modifier.testTag("navigation_preview_unavailable"))
        else Card(Modifier.fillMaxWidth().testTag("navigation_preview_projection")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(page.header.orEmpty())
                Text("下一個轉向：${page.line1.orEmpty()}")
                Text("圖示編號：${page.contentIcon}；色彩編號：${page.color}／${page.colorDark}")
                Text("合成畫面已編碼：${Gen1Projection.encode(page, 0).size} bytes；未送往車輛。")
            }
        }
        Text("手機導航頁可讀取已授權的 Maps 導航通知；此處仍只有合成預覽，不會啟動導航或送往車輛，也不代表已通過實車驗證。", style = MaterialTheme.typography.bodySmall)
      }
    }
}
