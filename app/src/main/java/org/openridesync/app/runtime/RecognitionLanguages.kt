package org.openridesync.app.runtime

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.openridesync.app.R
import org.openridesync.app.auth.HondaCountrySnapshot
import org.json.JSONObject
import java.util.Locale

internal data class RecognitionLanguageSelection(val tag: String? = null, val revision: Long = 0)
internal enum class RecognitionLanguageQueryStage { IDLE, LOADING, COMPLETE, FAILED }
internal enum class RecognitionLanguageQuerySource { RECOGNITION_SUPPORT, LEGACY_DETAILS }
internal enum class RecognitionLanguageQueryError { UNAVAILABLE, UNSUPPORTED, PERMISSION_DENIED, TIMEOUT }
internal data class RecognitionProviderLanguages(val source: RecognitionLanguageQuerySource,
    val installed: List<String> = emptyList(), val pending: List<String> = emptyList(),
    val supported: List<String> = emptyList(), val online: List<String> = emptyList(),
    val legacy: List<String> = emptyList())
internal data class RecognitionLanguageSupport(val stage: RecognitionLanguageQueryStage = RecognitionLanguageQueryStage.IDLE,
    val provider: String? = null, val languageTag: String? = null,
    val languages: RecognitionProviderLanguages? = null, val error: RecognitionLanguageQueryError? = null)
/** Historical resolver provenance only; this is never evidence of current support or model installation. */
internal data class RecognitionLanguageResolution(val tag: String, val selectedTag: String?, val appLocale: String,
    val provider: String, val source: RecognitionLanguageQuerySource, val checkedAtMillis: Long,
    val countryCode: String? = null, val systemLocale: String? = null, val defaultLocale: String? = null)

internal fun canonicalRecognitionLanguage(raw: String): String? {
    if (raw.length !in 2..63 || !raw.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))) return null
    return runCatching { Locale.Builder().setLanguageTag(raw).build() }.getOrNull()
        ?.takeIf { it.language.isNotEmpty() && it.language != "und" }?.toLanguageTag()
}

/** Fresh OEM Companion.c control flow; the TW/HK/MO callbacks retain saved-Smali provenance. */
internal fun recognitionLanguageForLocale(locale: Locale, country: String? = null): String {
    if (locale.language.equals("zh", ignoreCase = true)) return when (country ?: locale.country) {
        "TW" -> "cmn-Hant-TW"
        "HK" -> "yue-hant-HK"
        "MO" -> "cmn-Hans-CN"
        else -> "cmn-Hans-${locale.country.ifEmpty { country.orEmpty() }}"
    }
    return Locale(locale.language, locale.country.ifEmpty { country.orEmpty() }).toLanguageTag()
}

/** Preserve raw equality and first occurrence order, including the OEM lower-case HK script. */
internal fun chooseAutomaticRecognitionLanguage(locale: Locale, country: String?, system: Locale,
    systemCandidate: String, candidates: List<String>): String {
    val ideal = recognitionLanguageForLocale(locale, country)
    if (ideal in candidates) return ideal
    val language = Locale.forLanguageTag(ideal).language
    if (language == system.language && systemCandidate in candidates) return systemCandidate
    return candidates.firstOrNull { Locale.forLanguageTag(it).language == language }
        ?: candidates.firstOrNull { it == "en-US" } ?: candidates.first()
}

internal fun resolveRecognitionLanguage(context: Context, tag: String?, country: String? = null): String {
    if (tag != null) {
        require(canonicalRecognitionLanguage(tag) != null || tag == "cmn-Hans-") { "Invalid recognition language" }
        return tag // Validation must not change a resolver candidate's raw case.
    }
    return recognitionLanguageForLocale(context.resources.configuration.locales[0], country)
}

private fun systemRecognitionLocale(): Locale = runCatching { Resources.getSystem().configuration.locales[0] }
    .getOrNull() ?: Locale.getDefault()
private fun localizedRecognitionCandidate(context: Context, locale: Locale): String =
    context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) })
        .getString(R.string.recognition_language_locale_candidate)

/** Resolver inputs are separate from sorted UI capability groups; synthesized entries are never support. */
internal fun recognitionResolverCandidates(languages: RecognitionProviderLanguages, defaultCandidate: String,
    defaultLanguage: String): List<String> {
    val raw = if (languages.source == RecognitionLanguageQuerySource.LEGACY_DETAILS) languages.legacy
        else languages.installed + languages.pending + languages.supported
    val candidates = raw.asSequence().take(1_536).filter { canonicalRecognitionLanguage(it) != null }.distinct().toList()
    return if (languages.source == RecognitionLanguageQuerySource.LEGACY_DETAILS) candidates.ifEmpty { listOf(defaultLanguage) }
        else (candidates + defaultCandidate).distinct()
}

private data class RecognitionEnvironment(val provider: String?, val locale: String, val system: String,
    val default: String, val country: HondaCountrySnapshot?)

/** Selection is private local storage, independent of account, App locale and TTS locale. Main-thread owner. */
internal class RecognitionLanguages(context: Context,
    private val providerIdentity: () -> String? = { recognitionProviderIdentity(context) },
    private val queryProvider: suspend (Context, String, String) -> RecognitionProviderLanguages = ::queryRecognitionProviderLanguages,
    private val country: StateFlow<HondaCountrySnapshot> = MutableStateFlow(HondaCountrySnapshot()),
) : AutoCloseable {
    val automaticCountry = country
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("recognition-language", Context.MODE_PRIVATE)
    private val mutableSelection = MutableStateFlow(RecognitionLanguageSelection(
        runCatching { preferences.getString("selected-tag", null) }.getOrNull()?.let(::canonicalRecognitionLanguage),
        runCatching { preferences.getLong("revision", 0) }.getOrDefault(0).coerceAtLeast(0)))
    val selection = mutableSelection.asStateFlow()
    private val mutableSupport = MutableStateFlow(RecognitionLanguageSupport())
    val support = mutableSupport.asStateFlow()
    private val mutableLastResolved = MutableStateFlow(readResolution())
    val lastResolved = mutableLastResolved.asStateFlow()
    private var environmentKey = environmentKey()
    @Volatile private var environmentEpoch = 0L
    val environmentRevision: Long get() = environmentEpoch
    private var generation = 0L
    private var task: Job? = null
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val providerObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { environmentChanged() }
    }
    private val configurationObserver = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { environmentChanged() }
        override fun onLowMemory() = Unit
    }
    private val packageObserver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.data?.schemeSpecificPart == ComponentName.unflattenFromString(environmentKey.provider.orEmpty().substringBefore('@'))?.packageName)
                environmentChanged()
        }
    }
    init {
        runCatching { this.context.contentResolver.registerContentObserver(Settings.Secure.getUriFor("voice_recognition_service"), false, providerObserver) }
        this.context.registerComponentCallbacks(configurationObserver)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(packageObserver, filter, Context.RECEIVER_NOT_EXPORTED)
            else this.context.registerReceiver(packageObserver, filter)
        }
        scope.launch { country.collect { refreshEnvironment() } }
    }

    fun select(tag: String?): Boolean {
        if (closed) return false
        val canonical = if (tag == null) null else canonicalRecognitionLanguage(tag) ?: return false
        val old = selection.value
        if (canonical == old.tag) return true
        if (old.revision == Long.MAX_VALUE) return false
        val next = old.copy(tag = canonical, revision = old.revision + 1)
        val previous = runCatching { preferences.all }.getOrElse { return false }
        if (!runCatching { preferences.edit().putString("selected-tag", canonical).putLong("revision", next.revision).commit() }.getOrDefault(false)) {
            // A failed disk commit may already have changed Android's in-memory preference view.
            runCatching {
                val rollback = preferences.edit()
                for (key in listOf("selected-tag", "revision")) when (val value = previous[key]) {
                    null -> rollback.remove(key)
                    is String -> rollback.putString(key, value)
                    is Long -> rollback.putLong(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Float -> rollback.putFloat(key, value)
                    is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                rollback.commit()
            }
            return false // A rollback attempt does not turn the original failure into a successful save.
        }
        cancelQuery()
        mutableSelection.value = next
        refreshEnvironment()
        return true
    }

    private fun environmentKey() = RecognitionEnvironment(providerIdentity(),
        context.resources.configuration.locales[0].toLanguageTag(), systemRecognitionLocale().toLanguageTag(),
        Locale.getDefault().toLanguageTag(), country.value.takeIf { selection.value.tag == null })
    fun initialAutomaticLanguage(): String = resolveRecognitionLanguage(context, null, country.value.code)

    /** Explicit voice action only. A timeout/error fallback is a request, never provider support. */
    suspend fun resolveForRecognition(valid: () -> Boolean): String = withContext(Dispatchers.Main.immediate) {
        refreshEnvironment()
        val selected = selection.value
        val key = environmentKey
        val epoch = environmentEpoch
        fun current() = !closed && valid() && selected == selection.value && epoch == environmentEpoch && key == environmentKey()
        fun checkCurrent() { if (!current()) throw RideRecognitionFailure(RideRecognitionError.INTERRUPTED) }
        checkCurrent()
        selected.tag?.let { return@withContext it }
        val fallback = recognitionLanguageForLocale(Locale.forLanguageTag(key.default))
        val resolved = if (key.provider == null) fallback else try {
            // The OEM API33 race supplies a default-language singleton after 200ms.
            val result = withTimeout(if (Build.VERSION.SDK_INT >= 33) 200L else 8_000L) {
                queryProvider(context, key.provider, resolveRecognitionLanguage(context, null, key.country?.code))
            }
            chooseAutomaticRecognitionLanguage(Locale.forLanguageTag(key.locale), key.country?.code,
                Locale.forLanguageTag(key.system), localizedRecognitionCandidate(context, Locale.forLanguageTag(key.system)),
                recognitionResolverCandidates(result, localizedRecognitionCandidate(context, Locale.forLanguageTag(key.default)), fallback))
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            fallback
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fallback }
        checkCurrent()
        resolved
    }
    private fun environmentChanged() {
        if (closed) return
        environmentEpoch++ // An observed A-to-B-to-A event still fences an earlier request.
        environmentKey = environmentKey()
        cancelQuery()
    }
    fun refreshEnvironment() {
        if (closed) return
        val next = environmentKey()
        if (next != environmentKey) {
            environmentKey = next
            environmentEpoch++
            cancelQuery()
        }
    }

    suspend fun query() = withContext(Dispatchers.Main.immediate) {
        if (closed || task != null) return@withContext
        refreshEnvironment()
        val provider = environmentKey.provider
        if (provider == null) {
            mutableSupport.value = RecognitionLanguageSupport(RecognitionLanguageQueryStage.FAILED,
                error = RecognitionLanguageQueryError.UNAVAILABLE)
            return@withContext
        }
        val selected = selection.value
        val key = environmentKey
        val language = resolveRecognitionLanguage(context, selected.tag, key.country?.code)
        val ticket = ++generation
        val owner = currentCoroutineContext().job
        task = owner
        fun valid() = !closed && generation == ticket && selection.value == selected && key == environmentKey()
        fun failure(error: RecognitionLanguageQueryError) {
            if (valid()) mutableSupport.value = RecognitionLanguageSupport(RecognitionLanguageQueryStage.FAILED, provider, language, error = error)
            else refreshEnvironment()
        }
        mutableSupport.value = RecognitionLanguageSupport(RecognitionLanguageQueryStage.LOADING, provider, language)
        try {
            val languages = withTimeout(8_000) { queryProvider(context, provider, language) }
            owner.ensureActive()
            if (!valid()) { refreshEnvironment(); return@withContext }
            val observed = languages.copy(installed = safeLanguageTags(languages.installed), pending = safeLanguageTags(languages.pending),
                supported = safeLanguageTags(languages.supported), online = safeLanguageTags(languages.online), legacy = safeLanguageTags(languages.legacy))
            val resolved = selected.tag ?: chooseAutomaticRecognitionLanguage(Locale.forLanguageTag(key.locale), key.country?.code,
                Locale.forLanguageTag(key.system), localizedRecognitionCandidate(context, Locale.forLanguageTag(key.system)),
                recognitionResolverCandidates(languages, localizedRecognitionCandidate(context, Locale.forLanguageTag(key.default)),
                    recognitionLanguageForLocale(Locale.forLanguageTag(key.default))))
            val resolution = RecognitionLanguageResolution(resolved, selected.tag, key.locale, provider, observed.source,
                System.currentTimeMillis(), key.country?.code, key.system, key.default)
            val encoded = JSONObject().put("tag", resolution.tag).put("selectedTag", resolution.selectedTag ?: JSONObject.NULL)
                .put("appLocale", resolution.appLocale).put("provider", resolution.provider).put("source", resolution.source.name)
                .put("checkedAtMillis", resolution.checkedAtMillis).put("countryCode", resolution.countryCode ?: JSONObject.NULL)
                .put("systemLocale", resolution.systemLocale ?: JSONObject.NULL)
                .put("defaultLocale", resolution.defaultLocale ?: JSONObject.NULL).toString()
            if (preferences.edit().putString("last-resolved", encoded).commit()) mutableLastResolved.value = resolution
            mutableSupport.value = RecognitionLanguageSupport(RecognitionLanguageQueryStage.COMPLETE, provider, language, observed)
        } catch (timeout: TimeoutCancellationException) {
            owner.ensureActive()
            failure(RecognitionLanguageQueryError.TIMEOUT)
        } catch (cancelled: CancellationException) {
            if (generation == ticket) mutableSupport.value = RecognitionLanguageSupport()
            throw cancelled
        } catch (error: Exception) {
            failure((error as? RecognitionLanguageQueryFailure)?.code ?: if (error is SecurityException)
                RecognitionLanguageQueryError.PERMISSION_DENIED else RecognitionLanguageQueryError.UNAVAILABLE)
        } finally { if (task === owner) task = null }
    }

    fun cancelQuery() {
        generation++
        mutableSupport.value = RecognitionLanguageSupport()
        val previous = task; task = null; previous?.cancel()
    }
    private fun readResolution(): RecognitionLanguageResolution? = runCatching {
        val raw = preferences.getString("last-resolved", null) ?: return@runCatching null
        val value = JSONObject(raw)
        RecognitionLanguageResolution(value.getString("tag").also { require(canonicalRecognitionLanguage(it) != null || it == "cmn-Hans-") },
            if (value.isNull("selectedTag")) null else requireNotNull(canonicalRecognitionLanguage(value.getString("selectedTag"))),
            value.getString("appLocale").takeIf { it.length <= 63 } ?: return@runCatching null,
            value.getString("provider").takeIf { it.length <= 512 } ?: return@runCatching null,
            RecognitionLanguageQuerySource.valueOf(value.getString("source")), value.getLong("checkedAtMillis"),
            value.optString("countryCode").takeIf { it.matches(Regex("[A-Z]{2}")) },
            value.optString("systemLocale").takeIf { canonicalRecognitionLanguage(it) != null },
            value.optString("defaultLocale").takeIf { canonicalRecognitionLanguage(it) != null })
    }.getOrNull()
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        environmentEpoch++
        cancelQuery()
        runCatching { context.contentResolver.unregisterContentObserver(providerObserver) }
        context.unregisterComponentCallbacks(configurationObserver)
        runCatching { context.unregisterReceiver(packageObserver) }
    }
}

private fun safeLanguageTags(tags: List<String>) = tags.asSequence().take(512).mapNotNull(::canonicalRecognitionLanguage).distinct().sorted().toList()
private class RecognitionLanguageQueryFailure(val code: RecognitionLanguageQueryError) : Exception(code.name)

/** Same setting Android's default SpeechRecognizer reads; version provenance also expires capability results. */
private fun recognitionProviderIdentity(context: Context): String? = runCatching {
    val component = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        ?.let(ComponentName::unflattenFromString) ?: return@runCatching null
    val updated = context.packageManager.getPackageInfo(component.packageName, 0).lastUpdateTime
    "${component.flattenToString()}@$updated"
}.getOrNull()

/** Capability query only: never startListening, ask for permission, change provider or download models. */
private suspend fun queryRecognitionProviderLanguages(context: Context, provider: String, language: String): RecognitionProviderLanguages {
    val component = ComponentName.unflattenFromString(provider.substringBefore('@'))
        ?: throw RecognitionLanguageQueryFailure(RecognitionLanguageQueryError.UNAVAILABLE)
    val done = CompletableDeferred<RecognitionProviderLanguages>()
    if (Build.VERSION.SDK_INT >= 33) {
        fun fail(error: Int) {
            done.completeExceptionally(RecognitionLanguageQueryFailure(when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionLanguageQueryError.PERMISSION_DENIED
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> RecognitionLanguageQueryError.UNSUPPORTED
                else -> RecognitionLanguageQueryError.UNAVAILABLE
            }))
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context, component)
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) { fail(error) }
                override fun onResults(results: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            recognizer.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1), context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        done.complete(RecognitionProviderLanguages(RecognitionLanguageQuerySource.RECOGNITION_SUPPORT,
                            support.installedOnDeviceLanguages, support.pendingOnDeviceLanguages,
                            support.supportedOnDeviceLanguages, support.onlineLanguages))
                    }
                    override fun onError(error: Int) { fail(error) }
                })
            return done.await()
        } finally { done.cancel(); runCatching { recognizer.destroy() } }
    }
    val details = RecognizerIntent.getVoiceDetailsIntent(context)
        ?.takeIf { it.component?.packageName == component.packageName }
        ?: throw RecognitionLanguageQueryFailure(RecognitionLanguageQueryError.UNSUPPORTED)
    context.sendOrderedBroadcast(details, null, object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tags = runCatching { getResultExtras(false)?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES) }.getOrNull()
            if (tags == null) done.completeExceptionally(RecognitionLanguageQueryFailure(RecognitionLanguageQueryError.UNSUPPORTED))
            else done.complete(RecognitionProviderLanguages(RecognitionLanguageQuerySource.LEGACY_DETAILS, legacy = tags))
        }
    }, Handler(Looper.getMainLooper()), 0, null, null)
    return try { done.await() } finally { done.cancel() }
}
