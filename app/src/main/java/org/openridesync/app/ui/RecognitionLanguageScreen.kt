package org.openridesync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.openridesync.app.R
import org.openridesync.app.runtime.*
import java.util.Locale

/** Preference changes and explicit capability queries only. Opening this screen never starts recognition. */
@Composable
internal fun RecognitionLanguageScreen(onBack: () -> Unit, languages: RecognitionLanguages) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val selected by languages.selection.collectAsState()
    val support by languages.support.collectAsState()
    val previous by languages.lastResolved.collectAsState()
    val country by languages.automaticCountry.collectAsState()
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var saveFailed by remember { mutableStateOf(false) }
    DisposableEffect(owner, languages) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) languages.refreshEnvironment()
            if (event == Lifecycle.Event.ON_STOP) languages.cancelQuery()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) languages.refreshEnvironment()
        onDispose { owner.lifecycle.removeObserver(observer); languages.cancelQuery() }
    }
    val observed = support.languages
    val choices = (listOf("cmn-Hant-TW", "yue-Hant-HK", "cmn-Hans-CN", "en-US") + listOfNotNull(selected.tag) +
        observed?.let { it.installed + it.pending + it.supported + it.online + it.legacy }.orEmpty()).distinct()
    fun choose(tag: String?) { saveFailed = !languages.select(tag) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)
        .testTag("recognition_language_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.recognition_language_back)) }
        Text(stringResource(R.string.recognition_language_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.recognition_language_explanation))
        Text(stringResource(R.string.recognition_language_local_policy))
        @Composable fun Choice(tag: String?, title: String) {
            Row(Modifier.fillMaxWidth().selectable(selected.tag == tag, onClick = { choose(tag) }, role = Role.RadioButton)
                .testTag("recognition_language_${tag ?: "automatic"}").padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected.tag == tag, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (tag != null) Text(tag, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Choice(null, stringResource(R.string.recognition_language_automatic))
        Text(stringResource(R.string.recognition_language_automatic_request, resolveRecognitionLanguage(context, null, country.code)),
            style = MaterialTheme.typography.bodySmall)
        selected.tag?.let { tag -> Choice(tag, Locale.forLanguageTag(tag).getDisplayName(locale)) }
        if (saveFailed) Text(stringResource(R.string.recognition_language_save_failed),
            modifier = Modifier.testTag("recognition_language_save_failed"), color = MaterialTheme.colorScheme.error)
        Button(onClick = { scope.launch { languages.query() } },
            enabled = support.stage != RecognitionLanguageQueryStage.LOADING,
            modifier = Modifier.testTag("recognition_language_query")) { Text(stringResource(R.string.recognition_language_query)) }
        Text(stringResource(R.string.recognition_language_query_privacy), style = MaterialTheme.typography.bodySmall)
        when (support.stage) {
            RecognitionLanguageQueryStage.LOADING -> Text(stringResource(R.string.recognition_language_loading))
            RecognitionLanguageQueryStage.FAILED -> Text(stringResource(when (support.error) {
                RecognitionLanguageQueryError.TIMEOUT -> R.string.recognition_language_timeout
                RecognitionLanguageQueryError.UNSUPPORTED -> R.string.recognition_language_query_unsupported
                RecognitionLanguageQueryError.PERMISSION_DENIED -> R.string.recognition_language_query_denied
                else -> R.string.recognition_language_query_unavailable
            }), Modifier.testTag("recognition_language_query_error"))
            RecognitionLanguageQueryStage.COMPLETE -> {
                Text(stringResource(R.string.recognition_language_provider, support.provider.orEmpty()))
                Text(stringResource(R.string.recognition_language_requested, support.languageTag.orEmpty()))
                @Composable fun Group(title: Int, tags: List<String>) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(if (tags.isEmpty()) stringResource(R.string.recognition_language_none_reported) else tags.joinToString(", "))
                }
                if (observed?.source == RecognitionLanguageQuerySource.LEGACY_DETAILS) {
                    Text(stringResource(R.string.recognition_language_legacy_warning))
                    Group(R.string.recognition_language_legacy, observed.legacy)
                } else if (observed != null) {
                    Group(R.string.recognition_language_installed, observed.installed)
                    Group(R.string.recognition_language_pending, observed.pending)
                    Group(R.string.recognition_language_supported, observed.supported)
                    Group(R.string.recognition_language_online, observed.online)
                }
                Text(stringResource(R.string.recognition_language_support_warning))
            }
            RecognitionLanguageQueryStage.IDLE -> Text(stringResource(R.string.recognition_language_not_queried))
        }
        choices.filter { it != selected.tag }.forEach { tag ->
            Choice(tag, Locale.forLanguageTag(tag).getDisplayName(locale))
        }
        previous?.let { cache ->
            HorizontalDivider()
            Text(stringResource(R.string.recognition_language_cached_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.recognition_language_cached, cache.tag, cache.appLocale, cache.provider, cache.source.name),
                Modifier.testTag("recognition_language_cached"), style = MaterialTheme.typography.bodySmall)
            if (cache.systemLocale != null && cache.defaultLocale != null)
                Text(stringResource(R.string.recognition_language_cached_environment,
                    cache.countryCode ?: "—", cache.systemLocale, cache.defaultLocale), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.recognition_language_cached_warning), style = MaterialTheme.typography.bodySmall)
        }
    }
}
