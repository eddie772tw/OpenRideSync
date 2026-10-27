package org.openridesync.app.media

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openridesync.app.OpenRideSyncApplication
import org.openridesync.app.auth.AuthSession
import org.openridesync.app.auth.AuthStatus
import org.openridesync.diagnostics.DiagnosticJournal.*

internal data class FavoriteMusicState(
    val signedIn: Boolean = false, val observing: Boolean = false, val loading: Boolean = false,
    val apps: List<FavoriteMusicApp> = emptyList(), val selected: String? = null,
    val error: Boolean = false, val staleSelection: Boolean = false,
)

internal class FavoriteMusicViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenRideSyncApplication
    private val auth = AuthSession.get()
    private val preferences = app.getSharedPreferences("music", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(FavoriteMusicState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var generation = 0L
    private var job: Job? = null

    init { viewModelScope.launch { auth.state.collect { if (visible) refresh() else stop() } } }
    fun onStart() { if (!visible) { visible = true; refresh() } }
    fun onStop() { visible = false; stop() }
    private fun stop() { generation++; job?.cancel(); job = null; mutableState.value = FavoriteMusicState() }

    fun refresh() = load(null, change = false)
    fun select(packageName: String?) {
        if (!visible || auth.currentUser() == null || auth.state.value.status != AuthStatus.SIGNED_IN) return
        if (packageName != null) { load(packageName, change = true); return }
        // Clearing an obsolete preference must work even if the package manager lookup is unavailable.
        stop()
        preferences.edit { remove("favorite") }
        app.journal?.record(Component.MEDIA, app.journal?.newOperation(), Event.STATE_CHANGE, Outcome.OBSERVED, ErrorCode.NONE)
        refresh()
    }

    private fun load(requested: String?, change: Boolean) {
        if (!visible) return
        stop()
        val uid = auth.currentUser()?.uid
        val revision = auth.revision
        if (uid == null || auth.state.value.status != AuthStatus.SIGNED_IN) return
        val current = generation
        fun live() = visible && generation == current && auth.currentUser()?.uid == uid &&
            auth.revision == revision && auth.state.value.status == AuthStatus.SIGNED_IN
        val selected = preferences.getString("favorite", null)?.takeIf { it.isNotEmpty() }
        mutableState.value = FavoriteMusicState(signedIn = true, observing = true, loading = true, selected = selected)
        val operation = app.journal?.newOperation()
        job = viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) { installedFavoriteMusic(app) }
                if (!live()) return@launch
                // Re-query before saving: a click on a stale/uninstalled row must not silently change the preference.
                val choice = if (change) favoriteMusicSelection(selected, requested, installed.map { it.packageName }) else selected
                if (change) preferences.edit { if (choice == null) remove("favorite") else putString("favorite", choice) }
                mutableState.value = FavoriteMusicState(true, true, apps = installed, selected = choice)
                app.journal?.record(Component.MEDIA, operation, if (change) Event.STATE_CHANGE else Event.REQUEST,
                    Outcome.OBSERVED, ErrorCode.NONE)
            } catch (error: CancellationException) { throw error }
            catch (_: IllegalArgumentException) {
                if (live()) mutableState.value = state.value.copy(loading = false, staleSelection = true)
                app.journal?.record(Component.MEDIA, operation, Event.STATE_CHANGE, Outcome.FAILED, ErrorCode.NOT_FOUND)
            } catch (_: RuntimeException) {
                if (live()) mutableState.value = state.value.copy(loading = false, error = true)
                app.journal?.record(Component.MEDIA, operation, Event.REQUEST, Outcome.FAILED, ErrorCode.UNAVAILABLE)
            }
        }
    }
    override fun onCleared() { onStop(); super.onCleared() }
}
