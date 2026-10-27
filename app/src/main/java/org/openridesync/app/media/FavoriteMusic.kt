package org.openridesync.app.media

import android.content.Context
import android.content.pm.PackageManager

/** OEM MusicAppProviderImpl.p production preference catalog, in original insertion order. */
internal val preferredMusicPackages = listOf(
    "com.amazon.mp3", "com.apple.android.music", "deezer.android.app", "fm.player",
    "com.maxmpz.audioplayer", "com.sec.android.app.music", "com.soundcloud.android",
    "com.spotify.music", "tunein.player", "org.videolan.vlc",
    "com.google.android.apps.youtube.music", "com.shaiban.audioplayer.mplayer",
)

internal data class FavoriteMusicApp(val packageName: String, val label: String)

/** Like ApplicationRegistry.b: leanback launcher first, then ordinary launcher. No app launch. */
internal fun installedFavoriteMusic(context: Context): List<FavoriteMusicApp> {
    val manager = context.packageManager
    return preferredMusicPackages.mapNotNull { name ->
        try {
            val intent = manager.getLeanbackLaunchIntentForPackage(name) ?: manager.getLaunchIntentForPackage(name)
                ?: return@mapNotNull null
            val activity = manager.queryIntentActivities(intent, PackageManager.GET_META_DATA).firstOrNull()?.activityInfo
                ?: return@mapNotNull null
            if (activity.packageName != name || !activity.enabled || !activity.applicationInfo.enabled || !activity.exported)
                return@mapNotNull null
            FavoriteMusicApp(name, boundedMediaText(activity.loadLabel(manager).toString()).ifBlank { name })
        } catch (_: PackageManager.NameNotFoundException) { null }
    }
}

/** SettingsMusicAppsViewModel.J: tapping the saved app again (or none) clears the choice. */
internal fun favoriteMusicSelection(current: String?, requested: String?, installed: Collection<String>): String? {
    if (requested == null) return null
    require(requested in preferredMusicPackages && requested in installed) { "Music app unavailable" }
    return requested.takeUnless { it == current }
}
