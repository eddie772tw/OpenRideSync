package org.openridesync.app.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.openridesync.app.ble.Gen1Input.Function
import org.openridesync.app.calls.*
import org.openridesync.app.media.MediaState
import org.openridesync.app.navigation.NavigationObservation

/** Phone labels; meter labels use RidePageLabels and the current App locale. */
internal val rideStatusChoices = listOf("天氣預報", "手機電量", "行動網路", "Wi-Fi")

/** Labels describe the shared business action, not the physical direction's name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RidePageActions(page: RidePageState, media: MediaState?, navigation: NavigationObservation,
    callInbox: CallInboxState, onAction: ((Function) -> Unit)?) {
    if (page.stage !in setOf(RidePageStage.MUSIC, RidePageStage.VOLUME, RidePageStage.WEATHER,
            RidePageStage.STATUS_MENU, RidePageStage.STATUS_REPORT,
            RidePageStage.MESSAGE, RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
            RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING, RidePageStage.MESSAGE_CONFIRMATION,
            RidePageStage.MESSAGE_TRY_AGAIN, RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT,
            RidePageStage.NAVIGATION, RidePageStage.NAVIGATION_CANCEL, RidePageStage.CALL, RidePageStage.CALL_CANCEL)) return
    val call = callInbox.calls.singleOrNull().takeIf { callInbox.access == CallAccess.READY }
    FlowRow(Modifier.fillMaxWidth().testTag("ride_page_actions"), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @Composable fun Action(label: String, function: Function, available: Boolean = true) {
            Button(onClick = { onAction?.invoke(function) }, enabled = onAction != null && available,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                modifier = Modifier.testTag("ride_action_${function.name}")) {
                Text(label, style = MaterialTheme.typography.titleLarge)
            }
        }
        when (page.stage) {
            RidePageStage.MUSIC -> {
                Action("播放／暫停", Function.BACK, media?.let { it.canPlay || it.canPause } == true)
                Action("下一首", Function.SELECT, media?.canNext == true)
            }
            RidePageStage.VOLUME -> {
                Action("音量 −", Function.NEXT, page.volumePercentage != null)
                Action("音量 ＋", Function.PREVIOUS, page.volumePercentage != null)
            }
            RidePageStage.STATUS_MENU -> {
                Action("上一項", Function.PREVIOUS, page.statusChoice > 0)
                Action("朗讀選取項目", Function.SELECT, page.statusCanReport)
                Action("下一項", Function.NEXT, page.statusChoice < rideStatusChoices.lastIndex)
                Action("返回", Function.BACK)
            }
            RidePageStage.STATUS_REPORT -> {
                if (page.statusReport?.stage == RideStatusReportStage.FAILED)
                    Action("重試匯報", Function.SELECT, page.statusCanReport)
                Action("音量 −", Function.NEXT, page.volumePercentage != null)
                Action("音量 ＋", Function.PREVIOUS, page.volumePercentage != null)
                Action("返回狀態選單", Function.BACK)
            }
            RidePageStage.WEATHER -> {
                if (page.weatherCanRetry) Action("重試天氣匯報", Function.SELECT)
                Action("音量 −", Function.NEXT, page.volumePercentage != null)
                Action("音量 ＋", Function.PREVIOUS, page.volumePercentage != null)
                Action("返回狀態選單", Function.BACK)
            }
            RidePageStage.MESSAGE_PROMPT -> {
                Action("讀取訊息", Function.SELECT, page.messageCanRead)
                Action("略過", Function.BACK)
            }
            RidePageStage.MESSAGE_READ -> {
                Action("回覆…", Function.SELECT, page.messageCanReply)
                Action("停止並繼續", Function.BACK)
            }
            RidePageStage.MESSAGE_CATEGORY -> {
                Action("上一項", Function.PREVIOUS, page.replyChoice > 0)
                Action(if (page.replyChoice == 0) "開始語音回覆" else "選擇快速回覆", Function.SELECT)
                Action("下一項", Function.NEXT, page.replyChoice < page.replyChoices.lastIndex)
                Action("取消回覆", Function.BACK)
            }
            RidePageStage.MESSAGE_QUICK_LIST -> {
                Action("上一句", Function.PREVIOUS, page.replyChoice > 0)
                Action("傳送選取回覆", Function.SELECT,
                    page.replyChoices.getOrNull(page.replyChoice)?.let(RideMessageReply::text) != null)
                Action("下一句", Function.NEXT, page.replyChoice < page.replyChoices.lastIndex)
                Action("返回回覆方式", Function.BACK)
            }
            RidePageStage.MESSAGE_LISTENING -> Action("取消錄音", Function.BACK)
            RidePageStage.MESSAGE_CONFIRMATION -> {
                Action("送出確認文字", Function.SELECT, !page.replyText.isNullOrBlank())
                Action("重新錄音", Function.BACK)
            }
            RidePageStage.MESSAGE_TRY_AGAIN -> {
                Action("重試語音", Function.SELECT)
                Action("取消回覆", Function.BACK)
            }
            RidePageStage.MESSAGE_SENDING -> Action("離開傳送狀態", Function.BACK)
            RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT -> Action("完成並繼續", Function.BACK)
            RidePageStage.MESSAGE -> {
                Action("上一則", Function.PREVIOUS)
                Action(if (page.messageCanReply) "回覆…" else "朗讀", Function.SELECT, page.messageCanReply || page.messageReadStatus != MessageReadStatus.READING)
                Action("下一則", Function.NEXT)
            }
            RidePageStage.NAVIGATION -> {
                Action("重播導航", Function.SELECT, navigation.canRepeat)
                Action("結束導航…", Function.BACK, navigation.canCancel)
            }
            RidePageStage.NAVIGATION_CANCEL -> {
                Action("確認結束導航", Function.SELECT, navigation.canCancel)
                Action("繼續導航", Function.BACK)
            }
            RidePageStage.CALL -> if (call?.kind == CallKind.INCOMING) {
                Action("接聽", Function.SELECT, CallAction.ANSWER in call.actions)
                Action("拒接", Function.BACK, CallAction.DECLINE in call.actions)
            } else Action("結束通話…", Function.BACK, call?.actions?.contains(CallAction.HANG_UP) == true)
            RidePageStage.CALL_CANCEL -> {
                Action("確認結束通話", Function.SELECT, call?.kind == CallKind.ONGOING && CallAction.HANG_UP in call.actions)
                Action("繼續通話", Function.BACK)
            }
            else -> Unit
        }
        Action(if (page.stage in setOf(RidePageStage.MESSAGE_PROMPT, RidePageStage.MESSAGE_READ,
                RidePageStage.MESSAGE_CATEGORY, RidePageStage.MESSAGE_QUICK_LIST, RidePageStage.MESSAGE_LISTENING, RidePageStage.MESSAGE_CONFIRMATION,
                RidePageStage.MESSAGE_TRY_AGAIN, RidePageStage.MESSAGE_SENDING, RidePageStage.MESSAGE_SENT, RidePageStage.MESSAGE_NOT_SENT))
            "清除待讀並回主選單" else "返回主選單", Function.MENU)
    }
}
