# Favorite-start host regression

This isolated harness compiles the **actual production** `FavoriteMusicStart.kt` and
`FavoriteSessionAcquisition.kt` and `FavoriteStartGate.kt` with small, explicitly synthetic Android/NativeMedia
doubles. These files are outside Gradle source sets and must never ship in an APK.
It needs JDK 17 and the Kotlin 2.3.20 compiler distribution (including its bundled
coroutines jar), with no Android SDK, phone, credentials or real player.

```sh
python apps/open-roadsync/android/host-tests/favorite-start/run.py \
  --java-home /path/to/jdk-17 --kotlin-home /path/to/kotlinc
```

Seven scenarios check returned status, PLAY count, owner transfer and cleanup:

- The original session lacks PLAY capability and is replaced during the next lookup:
  fail unavailable, never select/play the replacement.
- A second session appears inside selection's source refresh: report ambiguity.
- A second session appears inside final token verification: report ambiguity.
- A ready single session: exactly one PLAY request and one ownership transfer.
- Separate starter instances overlap: only the first launches/creates an observer; the
  second returns START_IN_PROGRESS without queued work. Cancelling the first closes
  its observer and a later explicit retry succeeds.
- A handled Activity launch failure releases acquisition ownership.
- An unexpected caller-guard exception also releases acquisition ownership.

On baseline `8e04be12`, the first scenario fails because the replacement receives
PLAY. The harness is orchestration evidence, **not** Android API compatibility,
MediaBrowser binding, actual callback timing, permissions, audio or vehicle evidence.
The doubles deliberately implement only calls required by the starter. Use normal
`:app:testDebugUnitTest`, `:app:lintDebug`, APK compilation and instrumented media
tests when the Android environment is available.

The separate `FavoriteSessionAcquisitionTest` in the normal app JVM test set covers
11 pure state-machine cases, including browser timeout/fallback, exact identity,
ambiguity, suspension and terminal-state protection. Four `FavoriteStartGateTest`
cases additionally cover competing threads, successful release and stale/foreign tickets.
