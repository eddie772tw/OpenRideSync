package org.openridesync.app.media
import android.content.Context
import kotlinx.coroutines.*
fun main() = runBlocking {
 for (mode in listOf("replacement", "ambiguous", "late-ambiguous", "ready")) {
  NativeMedia.mode = mode; NativeMedia.plays = 0; NativeMedia.closes = 0
  var attached = 0
  val result = FavoriteMusicStarter(Context(), null).start({ true }, { false }, { attached++; true })
  val expected = when(mode) { "replacement" -> FavoriteStartStatus.SESSION_UNAVAILABLE; "ready" -> FavoriteStartStatus.PLAY_REQUESTED; else -> FavoriteStartStatus.MULTIPLE_SESSIONS }
  check(result == expected) { "$mode: $result != $expected" }
  check(NativeMedia.plays == if(mode == "ready") 1 else 0)
  check(attached == if(mode == "ready") 1 else 0)
  check(NativeMedia.closes == if(mode == "ready") 0 else 1)
  println("PASS $mode: $result, plays=${NativeMedia.plays}, attachments=$attached")
 }

 // Separate starter instances represent the ride owner and explicit development-test caller.
 NativeMedia.mode = "waiting"; NativeMedia.plays = 0; NativeMedia.closes = 0; NativeMedia.created = 0
 Context.launches = 0
 var attached = 0
 val first = async(start = CoroutineStart.UNDISPATCHED) {
  FavoriteMusicStarter(Context(), null).start({ true }, { false }, { attached++; true })
 }
 withTimeout(5_000) { while (NativeMedia.created == 0) yield() }
 val competing = FavoriteMusicStarter(Context(), null).start({ true }, { false }, { attached++; true })
 check(competing == FavoriteStartStatus.START_IN_PROGRESS)
 check(NativeMedia.created == 1 && Context.launches == 1 && NativeMedia.plays == 0 && attached == 0)
 first.cancelAndJoin()
 check(NativeMedia.closes == 1)
 NativeMedia.mode = "ready"
 val retry = FavoriteMusicStarter(Context(), null).start({ true }, { false }, { attached++; true })
 check(retry == FavoriteStartStatus.PLAY_REQUESTED)
 check(NativeMedia.created == 2 && Context.launches == 2 && NativeMedia.plays == 1 && attached == 1)
 println("PASS overlapping instances rejected; cancellation releases ownership; explicit retry plays once")

 Context.rejectLaunch = true
 val rejected = FavoriteMusicStarter(Context(), null).start({ true }, { false }, { true })
 check(rejected == FavoriteStartStatus.OPEN_APP_REQUIRED)
 Context.rejectLaunch = false
 check(FavoriteMusicStarter(Context(), null).start({ true }, { false }, { true }) == FavoriteStartStatus.PLAY_REQUESTED)
 println("PASS handled launch failure releases ownership")

 val thrown = runCatching {
  FavoriteMusicStarter(Context(), null).start({ throw IllegalStateException("Synthetic caller failure") }, { false }, { true })
 }.exceptionOrNull()
 check(thrown is IllegalStateException)
 check(FavoriteMusicStarter(Context(), null).start({ true }, { false }, { true }) == FavoriteStartStatus.PLAY_REQUESTED)
 println("PASS unexpected caller exception releases ownership")
}
