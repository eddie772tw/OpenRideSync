package org.openridesync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openridesync.app.R
import org.openridesync.app.runtime.QuickReplyEntry
import org.openridesync.app.runtime.QuickReplyPreferences
import org.openridesync.app.runtime.RideMessageReply
import org.openridesync.app.runtime.quickReplyTexts

/** The draft is remember-only: back, cancel and disposal never persist it. */
@Composable
internal fun QuickReplySettingsScreen(onBack: () -> Unit, preferences: QuickReplyPreferences) {
    val context = LocalContext.current
    val invalid by preferences.invalidStorage.collectAsState()
    var draft by remember(preferences) { mutableStateOf(preferences.entries.value.toList()) }
    var recoveryChosen by remember(preferences) { mutableStateOf(false) }
    var editing by remember(preferences) { mutableStateOf<Int?>(null) }
    var editText by remember(preferences) { mutableStateOf("") }
    var restoring by remember(preferences) { mutableStateOf<Int?>(null) }
    var saving by remember(preferences) { mutableStateOf(false) }
    var result by remember(preferences) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val back = { if (!saving) onBack() }
    BackHandler(onBack = back)
    fun update(value: List<QuickReplyEntry>) { draft = value; result = 0 }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(16.dp)
            .testTag("quick_reply_settings"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = back, enabled = !saving, modifier = Modifier.testTag("quick_reply_cancel")) {
                Text(stringResource(R.string.quick_reply_cancel))
            }
            Text(stringResource(R.string.quick_reply_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.quick_reply_local_only))
            if (invalid) Text(stringResource(R.string.quick_reply_corrupt), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("quick_reply_corrupt"))
            if (result != 0) Text(stringResource(result), modifier = Modifier.testTag("quick_reply_save_result"),
                color = if (result == R.string.quick_reply_failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !saving, modifier = Modifier.testTag("quick_reply_restore_all"), onClick = {
                    update(QuickReplyPreferences.defaults.toList()); recoveryChosen = true
                }) { Text(stringResource(R.string.quick_reply_restore_all)) }
                Button(enabled = !saving && (!invalid || recoveryChosen), modifier = Modifier.testTag("quick_reply_save"), onClick = {
                    saving = true
                    val snapshot = draft.toList()
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { preferences.setEntries(snapshot) }
                        result = if (saved) R.string.quick_reply_saved else R.string.quick_reply_failed
                        saving = false
                    }
                }) { Text(stringResource(if (saving) R.string.quick_reply_saving else R.string.quick_reply_save)) }
            }
            LazyColumn(Modifier.weight(1f).testTag("quick_reply_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(draft) { index, entry ->
                    Card(Modifier.fillMaxWidth().testTag("quick_reply_item_$index")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.quick_reply_position, index + 1))
                            Text(quickReplyTexts(context, listOf(entry)).single(), modifier = Modifier.testTag("quick_reply_text_$index"))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = !saving && !invalid, modifier = Modifier.testTag("quick_reply_edit_$index"), onClick = {
                                    editText = quickReplyTexts(context, listOf(entry)).single(); editing = index
                                }) { Text(stringResource(R.string.quick_reply_edit)) }
                                TextButton(enabled = !saving && !invalid, modifier = Modifier.testTag("quick_reply_restore_$index"),
                                    onClick = { restoring = index }) { Text(stringResource(R.string.quick_reply_restore)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(enabled = !saving && !invalid && index > 0, modifier = Modifier.testTag("quick_reply_up_$index"), onClick = {
                                    update(draft.toMutableList().apply { add(index - 1, removeAt(index)) })
                                }) { Text(stringResource(R.string.quick_reply_up)) }
                                TextButton(enabled = !saving && !invalid && index < draft.lastIndex, modifier = Modifier.testTag("quick_reply_down_$index"), onClick = {
                                    update(draft.toMutableList().apply { add(index + 1, removeAt(index)) })
                                }) { Text(stringResource(R.string.quick_reply_down)) }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { index ->
        val valid = editText.length <= 2000 && RideMessageReply.text(editText) != null
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.quick_reply_edit)) },
            text = { Column {
                OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.testTag("quick_reply_editor"),
                    label = { Text(stringResource(R.string.quick_reply_message)) }, minLines = 2, maxLines = 6, isError = !valid)
                if (!valid) Text(stringResource(R.string.quick_reply_invalid_text))
            } }, confirmButton = {
                TextButton(enabled = valid, modifier = Modifier.testTag("quick_reply_edit_confirm"), onClick = {
                    update(draft.toMutableList().apply { set(index, QuickReplyEntry.User(editText)) }); editing = null
                }) { Text(stringResource(R.string.quick_reply_use_text)) }
            }, dismissButton = {
                TextButton(onClick = { editing = null }, modifier = Modifier.testTag("quick_reply_edit_cancel")) {
                    Text(stringResource(R.string.quick_reply_cancel))
                }
            })
    }
    restoring?.let { index ->
        // A User entry has no original default identity. Let the user choose instead of guessing from position.
        AlertDialog(onDismissRequest = { restoring = null }, title = { Text(stringResource(R.string.quick_reply_choose_default)) },
            text = { Column {
                QuickReplyPreferences.defaults.forEach { entry ->
                    val order = (entry as QuickReplyEntry.Default).order
                    TextButton(modifier = Modifier.testTag("quick_reply_default_$order"), onClick = {
                        update(draft.toMutableList().apply { set(index, entry) }); restoring = null
                    }) { Text(quickReplyTexts(context, listOf(entry)).single()) }
                }
            } }, confirmButton = {}, dismissButton = {
                TextButton(onClick = { restoring = null }) { Text(stringResource(R.string.quick_reply_cancel)) }
            })
    }
}
