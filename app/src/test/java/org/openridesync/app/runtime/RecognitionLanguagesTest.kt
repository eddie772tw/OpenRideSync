package org.openridesync.app.runtime

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.openridesync.app.auth.HondaCountrySnapshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** Real preference/query ownership, fake provider port. No microphone, model download or service call. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RecognitionLanguagesTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private var provider = "synthetic.recognizer/SyntheticService@1"
    private val owners = mutableListOf<RecognitionLanguages>()
    private val supported = RecognitionProviderLanguages(RecognitionLanguageQuerySource.RECOGNITION_SUPPORT,
        installed = listOf("en-US"), pending = listOf("cmn-Hant-TW"), supported = listOf("yue-Hant-HK"), online = listOf("cmn-Hans-CN"))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("recognition-language", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }
    @After fun cleanup() { owners.forEach { it.close() }; Dispatchers.resetMain() }
    private fun owner(query: suspend (Context, String, String) -> RecognitionProviderLanguages = { _, _, _ -> supported }) =
        RecognitionLanguages(context, { provider }, query).also { owners += it }

    @Test fun selectionPersistsAndRoundTripChangesInvalidateOldAttemptWithoutChangingAppLocale() {
        val appLocale = context.resources.configuration.locales[0]
        val languages = owner()
        assertNull(languages.selection.value.tag)
        assertTrue(languages.select("cmn-Hant-TW"))
        val first = languages.selection.value
        assertTrue(languages.select("yue-Hant-HK"))
        assertTrue(languages.select("cmn-Hant-TW"))
        assertEquals(first.tag, languages.selection.value.tag)
        assertTrue(languages.selection.value.revision > first.revision)
        val expected = languages.selection.value
        assertTrue(languages.select("cmn-Hant-TW"))
        assertEquals(expected, languages.selection.value)
        for (invalid in listOf("", "und", "en_US", "en-US\n", "<speak>", "en-")) assertFalse(languages.select(invalid))
        assertEquals(expected, languages.selection.value)
        languages.close()
        val reopened = owner()
        assertEquals(expected, reopened.selection.value)
        assertEquals(appLocale, context.resources.configuration.locales[0])
        assertTrue(reopened.select(null))
        assertNull(reopened.selection.value.tag)
        assertTrue(reopened.selection.value.revision > expected.revision)
    }

    @Test fun failedCommitRestoresRawPreferenceMemoryAndReopenedSelectionWithoutClaimingSuccess() {
        val stored = context.getSharedPreferences("recognition-language", Context.MODE_PRIVATE)
        val failing = object : SharedPreferences by stored {
            override fun edit(): SharedPreferences.Editor {
                val editor = stored.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                        editor.putLong(key, value)
                        return this
                    }
                    override fun commit(): Boolean { editor.commit(); return false }
                }
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "recognition-language") failing else super.getSharedPreferences(name, mode)
        }
        for (hasSelection in listOf(false, true)) {
            stored.edit().clear().apply {
                if (hasSelection) putString("selected-tag", "en-us").putLong("revision", 42L)
            }.commit()
            val previous = stored.all
            RecognitionLanguages(wrapped, { provider }).use { languages ->
                val baseline = languages.selection.value
                assertFalse(languages.select("cmn-Hant-TW"))
                assertEquals(baseline, languages.selection.value)
                assertEquals(previous, stored.all)
                RecognitionLanguages(context, { provider }).use { reopened ->
                    assertEquals(baseline, reopened.selection.value)
                }
            }
        }
    }

    @Test fun automaticMappingsAreRequestsAndExplicitSelectionDoesNotFollowAppOrCache() {
        fun appLocale(tag: String) = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        })
        assertEquals("cmn-Hant-TW", resolveRecognitionLanguage(appLocale("zh-TW"), null))
        assertEquals("yue-hant-HK", resolveRecognitionLanguage(appLocale("zh-HK"), null))
        assertEquals("cmn-Hans-CN", resolveRecognitionLanguage(appLocale("zh-CN"), null))
        assertEquals("cmn-Hans-CN", resolveRecognitionLanguage(appLocale("zh-MO"), null))
        assertEquals("de-DE", resolveRecognitionLanguage(appLocale("de-DE"), null))
        assertEquals("yue-Hant-HK", resolveRecognitionLanguage(appLocale("en-US"), "yue-Hant-HK"))
        assertThrows(IllegalArgumentException::class.java) { resolveRecognitionLanguage(context, "invalid_tag") }
    }

    @Test fun countryConversionAndOrderedFallbackFollowTheSavedRawLanguageContract() {
        fun ideal(locale: String, country: String? = null) = recognitionLanguageForLocale(Locale.forLanguageTag(locale), country)
        assertEquals("cmn-Hant-TW", ideal("zh-HK", "TW"))
        assertEquals("cmn-Hans-TW", ideal("zh-TW", "US"))
        assertEquals("cmn-Hans-CN", ideal("zh-MO"))
        assertEquals("cmn-Hans-SG", ideal("zh-SG"))
        assertEquals("cmn-Hans-", ideal("zh-Hant"))
        assertEquals("en-GB", ideal("en", "GB"))
        assertEquals("en-US", ideal("en-US", "GB"))
        assertEquals("sr-RS", ideal("sr-Latn-RS")) // OEM reconstructs language/region without script.
        assertEquals("cmn-TW", ideal("cmn-Hant-TW"))
        fun choose(locale: String, candidates: List<String>, system: String = "en-US", resource: String = "en-US") =
            chooseAutomaticRecognitionLanguage(Locale.forLanguageTag(locale), null, Locale.forLanguageTag(system), resource, candidates)
        assertEquals("yue-hant-HK", choose("zh-HK", listOf("yue-Hant-TW", "yue-hant-HK")))
        assertEquals("yue-Hant-TW", choose("zh-HK", listOf("yue-Hant-TW", "yue-Hant-HK")))
        assertEquals("en-GB", choose("en-AU", listOf("en-CA", "en-GB"), "en-GB", "en-GB"))
        assertEquals("en-CA", choose("en-AU", listOf("en-CA", "en-GB"), "fr-FR", "en-GB"))
        assertEquals("en-US", choose("de-DE", listOf("fr-FR", "en-US")))
        assertEquals("fr-FR", choose("de-DE", listOf("fr-FR", "en-us")))
        assertThrows(NoSuchElementException::class.java) { choose("de-DE", emptyList()) }
    }

    @Test fun resolverCandidatesPreserveRawOrderExcludeOnlineAndKeepSyntheticFallbackOutOfCapabilities() {
        val provider = RecognitionProviderLanguages(RecognitionLanguageQuerySource.RECOGNITION_SUPPORT,
            installed = listOf("en-GB", "yue-Hant-HK"), pending = listOf("en-GB", "en-US"),
            supported = listOf("yue-hant-HK", "unsafe\n"), online = listOf("de-DE"))
        assertEquals(listOf("en-GB", "yue-Hant-HK", "en-US", "yue-hant-HK", "zh-TW"),
            recognitionResolverCandidates(provider, "zh-TW", "cmn-Hant-TW"))
        assertEquals(listOf("en-GB", "yue-Hant-HK"), provider.installed)
        assertEquals(listOf("cmn-Hant-TW"), recognitionResolverCandidates(
            RecognitionProviderLanguages(RecognitionLanguageQuerySource.LEGACY_DETAILS), "zh-TW", "cmn-Hant-TW"))
    }

    @Test fun automaticVoiceActionQueriesOnlyOnRequestAndTimeoutFallsBackWithoutClaimingSupport() = runTest(dispatcher) {
        var requests = 0
        val languages = owner { _, _, _ -> requests++; awaitCancellation() }
        assertEquals(0, requests)
        languages.initialAutomaticLanguage()
        assertEquals(0, requests)
        val task = async { languages.resolveForRecognition { true } }
        runCurrent()
        assertEquals(1, requests)
        advanceTimeBy(201); runCurrent()
        assertEquals(recognitionLanguageForLocale(Locale.getDefault()), task.await())
        assertEquals(RecognitionLanguageSupport(), languages.support.value)
        assertNull(languages.lastResolved.value)
        assertTrue(languages.select("yue-Hant-HK"))
        assertEquals("yue-Hant-HK", languages.resolveForRecognition { true })
        assertEquals(1, requests)
    }

    @Test fun successfulAutomaticVoiceResolutionUsesRawCandidatesAndProviderFailureUsesDefaultWithoutCachingSupport() = runTest(dispatcher) {
        var fail = false
        val requested = resolveRecognitionLanguage(context, null)
        val languages = owner { _, _, tag ->
            assertEquals(requested, tag)
            if (fail) throw IllegalStateException("Synthetic provider failure")
            RecognitionProviderLanguages(RecognitionLanguageQuerySource.RECOGNITION_SUPPORT,
                pending = listOf(requested), online = listOf("fr-FR"))
        }
        assertEquals(requested, languages.resolveForRecognition { true })
        fail = true
        assertEquals(recognitionLanguageForLocale(Locale.getDefault()), languages.resolveForRecognition { true })
        assertEquals(RecognitionLanguageSupport(), languages.support.value)
        assertNull(languages.lastResolved.value)
        val expired = runCatching { languages.resolveForRecognition { false } }
        assertEquals(RideRecognitionError.INTERRUPTED, (expired.exceptionOrNull() as RideRecognitionFailure).code)
    }

    @Test fun automaticCountryRoundTripExpiresLateQueryAndVoiceResolutionButExplicitSelectionIgnoresIt() = runTest(dispatcher) {
        val country = MutableStateFlow(HondaCountrySnapshot("TW", 1, 1))
        val late = CompletableDeferred<Unit>()
        val languages = RecognitionLanguages(context, { provider }, { _, _, _ ->
            withContext(NonCancellable) { late.await() }; supported
        }, country).also { owners += it }
        val query = launch { languages.query() }
        val voice = async { runCatching { languages.resolveForRecognition { true } } }
        runCurrent()
        country.value = HondaCountrySnapshot("US", 2, 1)
        country.value = HondaCountrySnapshot("TW", 3, 1)
        runCurrent()
        late.complete(Unit); runCurrent(); query.join()
        assertEquals(RecognitionLanguageSupport(), languages.support.value)
        assertNull(languages.lastResolved.value)
        assertEquals(RideRecognitionError.INTERRUPTED, (voice.await().exceptionOrNull() as RideRecognitionFailure).code)
        assertTrue(languages.select("en-US"))
        val epoch = languages.environmentRevision
        languages.query()
        country.value = HondaCountrySnapshot(null, 4, 2)
        runCurrent(); languages.refreshEnvironment()
        assertEquals(epoch, languages.environmentRevision)
        assertEquals(RecognitionLanguageQueryStage.COMPLETE, languages.support.value.stage)
        assertEquals("en-US", languages.resolveForRecognition { true })
    }

    @Test fun capabilityKindsRemainDistinctAndHistoricalCacheNeverSelectsOrEstablishesCurrentSupport() = runTest(dispatcher) {
        var fail = false
        val languages = owner { _, queriedProvider, _ ->
            assertEquals(provider, queriedProvider)
            if (fail) throw IllegalStateException("Synthetic provider failure")
            supported
        }
        languages.query()
        assertNull(languages.selection.value.tag)
        assertEquals(supported, languages.support.value.languages)
        val cached = requireNotNull(languages.lastResolved.value)
        assertNull(cached.selectedTag)
        assertEquals(provider, cached.provider)
        assertTrue(languages.select("cmn-Hant-TW"))
        fail = true
        languages.query()
        assertEquals(RecognitionLanguageQueryStage.FAILED, languages.support.value.stage)
        assertEquals("cmn-Hant-TW", languages.selection.value.tag)
        assertEquals(cached, languages.lastResolved.value)
        val epoch = languages.environmentRevision
        provider = "synthetic.recognizer/SyntheticService@2"
        languages.refreshEnvironment()
        assertTrue(languages.environmentRevision > epoch)
        assertEquals(RecognitionLanguageSupport(), languages.support.value)
        assertEquals(cached, languages.lastResolved.value)
        languages.close()
        val reopened = owner()
        assertEquals("cmn-Hant-TW", reopened.selection.value.tag)
        assertEquals(cached, reopened.lastResolved.value)
        assertEquals(RecognitionLanguageSupport(), reopened.support.value)
    }

    @Test fun queryTimeoutDoesNotReplaceSelectedLanguageOrStartMicrophone() = runTest(dispatcher) {
        val languages = owner { _, _, _ -> awaitCancellation() }
        assertTrue(languages.select("cmn-Hant-TW"))
        val selected = languages.selection.value
        val job = launch { languages.query() }
        runCurrent()
        assertEquals(RecognitionLanguageQueryStage.LOADING, languages.support.value.stage)
        advanceTimeBy(8_001); runCurrent(); job.join()
        assertEquals(RecognitionLanguageQueryError.TIMEOUT, languages.support.value.error)
        assertEquals(selected, languages.selection.value)
        assertNull(languages.lastResolved.value)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
    }

    @Suppress("DEPRECATION")
    @Test fun appLocaleChangeExpiresCapabilitiesButHistoricalResolutionKeepsOriginalProvenance() = runTest(dispatcher) {
        val languages = owner()
        languages.query()
        val cached = languages.lastResolved.value
        val epoch = languages.environmentRevision
        val original = Configuration(context.resources.configuration)
        val other = if (original.locales[0].toLanguageTag() == "zh-HK") "de-DE" else "zh-HK"
        try {
            context.resources.updateConfiguration(Configuration(original).apply { setLocale(Locale.forLanguageTag(other)) }, context.resources.displayMetrics)
            languages.refreshEnvironment()
            assertTrue(languages.environmentRevision > epoch)
            assertEquals(RecognitionLanguageSupport(), languages.support.value)
            assertEquals(cached, languages.lastResolved.value)
            assertNull(languages.selection.value.tag)
        } finally {
            context.resources.updateConfiguration(original, context.resources.displayMetrics)
            languages.refreshEnvironment()
        }
    }

    @Test fun stoppedQueryCannotBlockOrOverwriteImmediateSuccessorWhenCallbackArrivesLate() = runTest(dispatcher) {
        val late = CompletableDeferred<Unit>()
        var requests = 0
        val languages = owner { _, _, _ ->
            requests++
            if (requests == 1) withContext(NonCancellable) { late.await() }
            supported
        }
        val old = launch { languages.query() }
        runCurrent(); languages.cancelQuery()
        languages.query()
        val successor = languages.support.value
        val cached = languages.lastResolved.value
        assertEquals(RecognitionLanguageQueryStage.COMPLETE, successor.stage)
        assertEquals(2, requests)
        late.complete(Unit); runCurrent(); old.join()
        assertEquals(successor, languages.support.value)
        assertEquals(cached, languages.lastResolved.value)
    }

    @Test fun preferenceProviderAndCloseInvalidateLateCapabilityResponses() = runTest(dispatcher) {
        for (change in 0..2) {
            val late = CompletableDeferred<Unit>()
            val languages = owner { _, _, _ -> withContext(NonCancellable) { late.await() }; supported }
            val old = launch { languages.query() }
            runCurrent()
            when (change) {
                0 -> languages.select("cmn-Hant-TW")
                1 -> { provider += "0"; languages.refreshEnvironment() }
                else -> languages.close()
            }
            late.complete(Unit); runCurrent(); old.join()
            assertEquals(RecognitionLanguageSupport(), languages.support.value)
            assertNull(languages.lastResolved.value)
        }
    }

    @Test fun malformedProviderTagsAreDiscardedAndLegacyDetailsNeverBecomeInstalledModels() = runTest(dispatcher) {
        val languages = owner { _, _, _ -> RecognitionProviderLanguages(RecognitionLanguageQuerySource.LEGACY_DETAILS,
            legacy = listOf("en-US", "en-US", "cmn-Hant-TW", "unsafe\n", "und")) }
        languages.query()
        val result = requireNotNull(languages.support.value.languages)
        assertEquals(listOf("cmn-Hant-TW", "en-US"), result.legacy)
        assertTrue(result.installed.isEmpty())
        assertTrue(result.pending.isEmpty())
        assertTrue(result.online.isEmpty())
    }
}
