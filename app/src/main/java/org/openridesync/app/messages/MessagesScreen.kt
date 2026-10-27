package org.openridesync.app.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openridesync.app.ui.AppAccess
import org.openridesync.app.ui.PermissionDialog
import org.openridesync.app.media.MediaAccessService

@Composable
internal fun MessagesScreen(onBack: () -> Unit, onAccount: () -> Unit, model: MessagesViewModel = viewModel()) {
    // The backend is lifecycle-scoped below. Keep receiving its ON_STOP clearing state.
    val state by model.state.collectAsState()
    val listener by MediaAccessService.callSummary.collectAsState()
    val context = LocalContext.current
    var permission by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onStart()
                Lifecycle.Event.ON_STOP -> model.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onStart()
        onDispose { owner.lifecycle.removeObserver(observer); model.onStop() }
    }
    val back = { model.onStop(); onBack() }
    BackHandler(onBack = back)
    if (permission) {
        val close = { permission = false; model.refresh() }
        PermissionDialog(listOf(AppAccess.MEDIA), onConfirm = close, onDismiss = close)
    }
    MessagesContent(state, back, onAccount, model::refresh, { permission = true }, model::reply,
        listenerDisconnected = AppAccess.MEDIA.granted(context) && !listener.listenerConnected)
}

@Composable
internal fun MessagesContent(state: MessagesPageState, onBack: () -> Unit, onAccount: () -> Unit,
    onRefresh: () -> Unit, onAccess: () -> Unit, onReply: (String, String) -> Unit,
    listenerDisconnected: Boolean = false) {
    var replying by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { replying = null; draft = ""; confirming = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val entries = if (state.signedIn && state.observing && !listenerDisconnected && state.inbox.access == MessageAccess.READY)
        state.inbox.messages else emptyList()
    val selected = entries.singleOrNull { it.id == replying && it.canReply }
    LaunchedEffect(selected?.id) { draft = ""; confirming = false }
    if (selected != null) {
        val message = selected
        BackHandler { replying = null }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (confirming) "確認傳送回覆" else "回覆訊息", style = MaterialTheme.typography.headlineMedium)
                Text(message.appName)
                Text(message.title)
                if (confirming) {
                    Text(draft, Modifier.testTag("message_reply_preview"))
                    Text("按下傳送後，會交由此訊息 App 的通知回覆動作處理。")
                } else {
                    OutlinedTextField(value = draft, onValueChange = { draft = it },
                        label = { Text("回覆內容") }, modifier = Modifier.fillMaxWidth().testTag("message_reply_text"),
                        isError = draft.length > 2000, minLines = 2, maxLines = 5)
                    Text("最多 2000 字元；離開頁面或通知變更時不保留草稿。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = draft.isNotBlank() && draft.length <= 2000,
                    modifier = Modifier.testTag(if (confirming) "message_reply_send" else "message_reply_review"),
                    onClick = {
                        if (confirming) { replying = null; onReply(message.id, draft) }
                        else confirming = true
                    }) { Text(if (confirming) "傳送" else "確認內容") }
                TextButton(onClick = { replying = null }, modifier = Modifier.testTag("message_reply_cancel")) { Text("取消") }
            }
        }
        return
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(20.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("messages_back")) { Text("返回") }
            Text("訊息", style = MaterialTheme.typography.headlineMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("messages_list")) {
                item {
                    Text("查看手機目前仍存在的訊息通知，最多顯示最近 50 則。本頁離開後不保留內容；實際 SAB 儀表連線另有待處理通知提示與讀取，處理方式可在儀表自訂選擇，這不是訊息歷史紀錄。")
                }
                if (!state.signedIn) item {
                    Text("請先登入，再開啟訊息功能。")
                    Button(onClick = onAccount, modifier = Modifier.testTag("messages_account")) { Text("前往帳號與登入") }
                } else {
                    item {
                        if (state.observing && listenerDisconnected) Text(
                            "通知存取已授權，但通知服務尚未連線。訊息功能暫時不可用；請查看通知存取說明與連線恢復方式。")
                        else when (state.inbox.access) {
                            MessageAccess.ACCESS_REQUIRED -> Text("尚未授予通知存取，無法讀取或回覆其他 App 的訊息。可先查看說明，再自行決定是否授權。")
                            MessageAccess.UNAVAILABLE -> Text("目前無法取得訊息通知。請確認通知存取已開啟，並重新整理。")
                            MessageAccess.READY -> if (entries.isEmpty()) Text("目前沒有可讀取的訊息通知。只有 App 提供的訊息內容會顯示；通知被移除後也會從本頁移除。")
                        }
                        OutlinedButton(onClick = onAccess, modifier = Modifier.testTag("messages_access")) {
                            Text(if (state.observing && listenerDisconnected) "查看連線恢復說明" else "通知存取說明與設定")
                        }
                        TextButton(onClick = onRefresh, modifier = Modifier.testTag("messages_refresh")) { Text("重新整理") }
                    }
                    state.result?.let { result -> item {
                        Text(when (result) {
                            MessageReplyResult.DISPATCHED -> "回覆請求已交給訊息 App；尚未確認對方收到。"
                            MessageReplyResult.STALE -> "原通知已變更或移除，沒有傳送。請重新選擇訊息。"
                            MessageReplyResult.UNSUPPORTED -> "此通知目前不提供可用的快速回覆，請到原訊息 App 處理。"
                            MessageReplyResult.ACCESS_REQUIRED -> "通知存取已變更，沒有傳送。請檢查系統設定。"
                            MessageReplyResult.UNAVAILABLE -> "無法確認回覆請求已交給訊息 App。請先到原 App 確認，再決定是否重試。"
                            MessageReplyResult.INVALID_TEXT -> "請輸入 1 至 2000 字元的非空白回覆。"
                            MessageReplyResult.LOCKED -> "請先解鎖手機，再確認回覆。"
                        }, Modifier.testTag("messages_result"))
                    } }
                    items(entries, key = { it.id }) { message ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(message.appName, style = MaterialTheme.typography.labelLarge)
                                Text(message.title, style = MaterialTheme.typography.titleMedium)
                                Text(message.text)
                                if (message.canReply) OutlinedButton(onClick = { replying = message.id },
                                    modifier = Modifier.testTag("message_reply_${message.id}")) { Text("回覆") }
                                else Text("此通知目前無可用的快速回覆。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { Text("本頁不會自動回覆或播報。實際 SAB 連線的新訊息依儀表自訂設定先詢問、自動讀取或忽略提示，也保留手動瀏覽；把手回覆與語音輸入仍在開發中。", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
