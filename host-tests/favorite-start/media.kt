package org.openridesync.app.media
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.openridesync.diagnostics.DiagnosticJournal
val preferredMusicPackages = listOf("player")
data class Player(val packageName: String)
fun installedFavoriteMusic(context: Context) = listOf(Player("player"))
object MediaAccessService { val callSummary = MutableStateFlow(CallSummary()) }
data class CallSummary(val unknown: Boolean = false)
enum class MediaAccess { REQUIRED, GRANTED, UNAVAILABLE }
enum class MediaPlayback { PLAYING, PAUSED }
enum class MediaBrowserStatus { NONE, CONNECTING, SUSPENDED, FAILED }
enum class DispatchResult { DISPATCHED, ACCESS_REQUIRED, UNAVAILABLE }
data class Choice(val id: String, val packageName: String = "player")
data class State(val access: MediaAccess = MediaAccess.GRANTED, val sessions: List<Choice> = listOf(Choice("original")), val selectedId: String? = null, val playback: MediaPlayback = MediaPlayback.PAUSED, val canPlay: Boolean = false)
class NativeMedia(context: Context, journal: DiagnosticJournal?) {
 companion object { var plays = 0; var closes = 0; var mode = "replacement"; var created = 0 }
 val state = MutableStateFlow(State(canPlay = mode !in listOf("replacement", "waiting")))
 val browserStatus = MutableStateFlow(MediaBrowserStatus.NONE)
 var refreshes = 0
 init { created++ }
 fun start() {}
 fun connectBrowser(chosen: String) {}
 fun stopBrowser(status: MediaBrowserStatus) { browserStatus.value = status }
 fun refresh() {
  refreshes++
  if (mode == "replacement" && refreshes >= 3) state.value = State(sessions = listOf(Choice("replacement")), canPlay = true)
 }
 fun select(id: String): Boolean {
  refresh()
  if (state.value.sessions.none { it.id == id }) return false
  state.value = state.value.copy(selectedId = id)
  if (mode == "ambiguous") state.value = state.value.copy(sessions = listOf(Choice(id), Choice("second")))
  return true
 }
 fun selectOnlyPackage(name: String): Boolean = state.value.sessions.singleOrNull()?.let { select(it.id) } ?: false
 fun selectedToken(): String? {
  if (mode == "late-ambiguous") state.value = state.value.copy(sessions = listOf(Choice("original"), Choice("second")))
  return state.value.selectedId
 }
 fun play(): DispatchResult { plays++; return DispatchResult.DISPATCHED }
 fun close() { closes++ }
}
