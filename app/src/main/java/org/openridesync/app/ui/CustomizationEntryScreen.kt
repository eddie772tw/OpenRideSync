package org.openridesync.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Transitional primary destination; all enabled actions already have real destinations. */
@Composable
internal fun CustomizationEntryScreen(onMusicApps: () -> Unit, onPreview: () -> Unit, developer: Boolean) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)
        .testTag("customization_entry"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("儀表自訂", style = MaterialTheme.typography.headlineMedium)
        Text("管理儀表使用的功能與資料來源；設定本身不會啟動車輛連線。")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("音樂來源", style = MaterialTheme.typography.titleLarge)
                Text("選擇已安裝的常用播放器。播放器仍需支援媒體服務與系統授權。")
                Button(onClick = onMusicApps, modifier = Modifier.testTag("customize_music")) { Text("常用播放器") }
            }
        }
        Text("功能排序與資訊朗讀設定正在本次改版接入實際儀表行為，尚未顯示無作用的開關。", style = MaterialTheme.typography.bodyMedium)
        if (developer) OutlinedButton(onClick = onPreview, modifier = Modifier.testTag("customize_preview")) { Text("查看儀表開發預覽") }
    }
}
