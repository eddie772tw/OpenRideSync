package org.openridesync.app.navigation

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import java.util.UUID

internal const val MAPS_PACKAGE = "com.google.android.apps.maps"
internal data class MapsInstruction(
    val instruction: String,
    val nextStep: String?,
    val remaining: String?,
    val maneuver: NavigationProjection.Maneuver?,
)

/** Exact, bounded notification snapshot. Only the opaque ID derived from it reaches UI state. */
internal data class MapsSourceSignature(
    val key: String, val packageName: String, val uid: Int, val user: UserHandle, val postTime: Long,
    val whenTime: Long, val flags: Int, val category: String?,
    val title: String?, val text: String?, val subText: String?, val bigText: String?,
    val template: String?, val shortCriticalText: String?,
    val actions: List<Pair<String?, PendingIntent?>>,
    val maneuver: NavigationProjection.Maneuver?,
)

internal data class MapsSource(
    val signature: MapsSourceSignature,
    val instruction: MapsInstruction?,
    val exitAction: PendingIntent?,
    val id: String = "",
)

internal fun reconcileMapsSource(previous: MapsSource?, next: MapsSource?): MapsSource? =
    next?.copy(id = previous?.takeIf { it.signature == next.signature }?.id ?: UUID.randomUUID().toString())

/** Literal separators from the installed Maps locale; never execute resource text as a regex. */
internal fun mapsSeparator(format: String?, fallback: String): String {
    if (format == null || format.length > 512) return fallback
    val start = format.indexOf("%1\$s")
    val end = format.indexOf("%2\$s", start + 4)
    return if (start >= 0 && end > start + 4) format.substring(start + 4, end) else fallback
}

/** An observed ongoing Maps navigation notification, not proof of an owned or calculated route. */
internal fun isMapsNavigation(sbn: StatusBarNotification, exitLabel: String?): Boolean = try {
    val n = sbn.notification
    sbn.packageName == MAPS_PACKAGE && sbn.user == Process.myUserHandle() &&
        n.flags and Notification.FLAG_ONGOING_EVENT != 0 &&
        n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
        (n.category == "navigation" || (!exitLabel.isNullOrBlank() && n.actions.orEmpty().any {
            it.title?.toString() == exitLabel && it.actionIntent?.creatorPackage == MAPS_PACKAGE &&
                it.actionIntent.creatorUid == sbn.uid
        }))
} catch (_: RuntimeException) { false }

internal fun mapsInstruction(n: Notification, sdk: Int, directions: String, summary: String,
    maneuver: NavigationProjection.Maneuver?): MapsInstruction? = try {
    val e = n.extras
    if (e.getCharSequence(Notification.EXTRA_BIG_TEXT) != null) return null
    fun text(key: String): String? = e.getCharSequence(key)?.let {
        if (it.length > 4096) null else it.toString().takeIf(String::isNotBlank)
    }
    val title = text(Notification.EXTRA_TITLE) ?: return null
    val progress = sdk >= 36 && e.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$ProgressStyle"
    val parts = title.split(if (progress) summary else directions, limit = 2)
    val instruction = if (parts.size == 2) parts[1].trim() else if (progress) title else text(Notification.EXTRA_TEXT)
    if (instruction.isNullOrBlank()) return null
    MapsInstruction(instruction,
        if (progress) text("android.shortCriticalText") else parts[0].trim().takeIf(String::isNotBlank),
        text(Notification.EXTRA_SUB_TEXT), maneuver)
} catch (_: RuntimeException) { null }

/** No category fallback: a stop request must name one localized Maps exit action. */
internal fun uniqueExitAction(actions: List<Pair<String?, PendingIntent?>>, label: String?,
    packageName: String, uid: Int, user: UserHandle): PendingIntent? = try {
    if (label.isNullOrBlank() || label.length > 512) return null
    actions.filter { it.first == label }.singleOrNull()?.second?.takeIf {
        it.creatorPackage == packageName && it.creatorUid == uid && it.creatorUserHandle == user
    }
} catch (_: RuntimeException) { null }

internal fun mapsSource(sbn: StatusBarNotification, exitLabel: String?, directions: String,
    summary: String, maneuver: NavigationProjection.Maneuver?): MapsSource? = try {
    if (!isMapsNavigation(sbn, exitLabel) || sbn.key.length > 1024) return null
    val n = sbn.notification
    val extras = n.extras
    fun bounded(value: CharSequence?, limit: Int): String? {
        if (value != null && value.length > limit) throw IllegalArgumentException()
        return value?.toString()
    }
    val actions = n.actions.orEmpty()
    if (actions.size > 16) return null
    val contracts = actions.map { bounded(it.title, 512) to it.actionIntent }
    val pending = uniqueExitAction(contracts, exitLabel, MAPS_PACKAGE, sbn.uid, sbn.user)
    val signature = MapsSourceSignature(sbn.key, sbn.packageName, sbn.uid, sbn.user, sbn.postTime, n.`when`,
        n.flags, bounded(n.category, 512), bounded(extras.getCharSequence(Notification.EXTRA_TITLE), 4096),
        bounded(extras.getCharSequence(Notification.EXTRA_TEXT), 4096),
        bounded(extras.getCharSequence(Notification.EXTRA_SUB_TEXT), 4096),
        bounded(extras.getCharSequence(Notification.EXTRA_BIG_TEXT), 4096),
        bounded(extras.getString(Notification.EXTRA_TEMPLATE), 512),
        bounded(extras.getCharSequence("android.shortCriticalText"), 4096), contracts, maneuver)
    MapsSource(signature, mapsInstruction(n, Build.VERSION.SDK_INT, directions, summary, maneuver), pending)
} catch (_: RuntimeException) { null }

/** No URI icons, network, notification action dispatch or retained bitmap references. */
internal fun mapsNotificationBitmap(context: Context, n: Notification): Bitmap? = try {
    val icon = if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(Notification.EXTRA_LARGE_ICON, android.os.Parcelable::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
    when (icon) {
        is Bitmap -> icon.takeIf { !it.isRecycled && it.width in 1..1024 && it.height == it.width }
        is Icon -> {
            if (icon.type !in setOf(Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP, Icon.TYPE_RESOURCE)) return null
            val drawable = icon.loadDrawable(context) ?: return null
            val size = drawable.intrinsicWidth
            if (size !in 1..1024 || drawable.intrinsicHeight != size) return null
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(it))
            }
        }
        else -> null
    }
} catch (_: RuntimeException) { null }
