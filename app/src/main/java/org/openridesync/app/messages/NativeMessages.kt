package org.openridesync.app.messages

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.openridesync.app.media.MediaAccessService
import org.openridesync.diagnostics.DiagnosticJournal
import org.openridesync.diagnostics.DiagnosticJournal.Component
import org.openridesync.diagnostics.DiagnosticJournal.ErrorCode
import org.openridesync.diagnostics.DiagnosticJournal.Event
import org.openridesync.diagnostics.DiagnosticJournal.Outcome
import java.util.UUID
import java.security.MessageDigest
import java.nio.ByteBuffer

internal enum class MessageAccess { ACCESS_REQUIRED, UNAVAILABLE, READY }
internal data class MessageItem(
    val id: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val canReply: Boolean,
    val sender: String? = null,
    val postedAt: Long = timestamp,
    val recoveryId: String = id,
)
internal data class MessageInboxState(
    val access: MessageAccess = MessageAccess.UNAVAILABLE,
    val messages: List<MessageItem> = emptyList(),
)
internal enum class MessageReplyResult { DISPATCHED, STALE, UNSUPPORTED, ACCESS_REQUIRED, UNAVAILABLE, INVALID_TEXT, LOCKED }

private const val MAX_MESSAGES = 50
private const val MAX_TEXT = 512
private const val MAX_SOURCE_TEXT = 4096
private const val MAX_REPLY = 2000

/** All content and action references stay in the lifetime of this explicit phone-page or ride-mode owner. */
internal class NativeMessages(context: Context, private val journal: DiagnosticJournal? = null) : AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)
    private val keyguard = this.context.getSystemService(KeyguardManager::class.java)
    private val component = ComponentName(this.context, MediaAccessService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(MessageInboxState())
    val state: StateFlow<MessageInboxState> = mutableState.asStateFlow()
    private var entries = emptyMap<String, MessageSource>()
    private val spent = mutableSetOf<String>()
    private var running = false
    private var closed = false

    fun start() {
        mainThread()
        check(!closed) { "Message owner is closed" }
        if (running) return
        running = true
        scope.launch { MediaAccessService.changes.drop(1).collect { refresh() } }
        scope.launch {
            var granted = hasAccess()
            while (running) {
                delay(1_000)
                val current = hasAccess()
                if (current != granted) {
                    granted = current
                    refresh()
                    if (current) rebind()
                }
            }
        }
        refresh()
        if (hasAccess()) rebind()
    }

    fun refresh() {
        mainThread()
        if (!running || closed) return
        try {
            if (manager == null) { clear(MessageAccess.UNAVAILABLE); return }
            if (!hasAccess()) {
                clear(MessageAccess.ACCESS_REQUIRED); return
            }
            val notifications = MediaAccessService.readActiveNotifications() ?: run {
                clear(MessageAccess.UNAVAILABLE); return
            }
            if (notifications.size > 512) { clear(MessageAccess.UNAVAILABLE); return }
            val next = reconcileMessages(entries, notifications)
            entries = next
            spent.retainAll(next.keys)
            mutableState.value = MessageInboxState(MessageAccess.READY, next.values
                .sortedByDescending { it.timestamp }.take(MAX_MESSAGES).map { source ->
                    MessageItem(source.id, appLabel(source.packageName), source.packageName,
                        source.title, source.text, source.timestamp, source.action != null && source.id !in spent,
                        source.sender, source.signature.postTime, messageRecoveryId(source))
                })
        } catch (_: SecurityException) { clear(MessageAccess.ACCESS_REQUIRED) }
        catch (_: RuntimeException) { clear(MessageAccess.UNAVAILABLE) }
    }

    fun reply(id: String, text: String): MessageReplyResult = performReply(id, text, false) { true }

    /** Only an explicit, confirmed SAB reply owner may act while locked. The phone editor stays locked.
     * Recheck after refreshing the notification: an earlier recognition/confirmation is not authority. */
    fun replyFromRide(id: String, text: String, valid: () -> Boolean): MessageReplyResult =
        performReply(id, text, true, valid)

    private fun performReply(id: String, text: String, allowLocked: Boolean, valid: () -> Boolean): MessageReplyResult {
        mainThread()
        val operation = journal?.newOperation()
        journal?.record(Component.NOTIFICATION, operation, Event.REQUEST, Outcome.STARTED, ErrorCode.NONE)
        val result = try {
            val before = entries[id]
            refresh() // Re-read the live listener and verify this exact source and action again.
            when {
                !running || closed -> MessageReplyResult.UNAVAILABLE
                state.value.access == MessageAccess.ACCESS_REQUIRED -> MessageReplyResult.ACCESS_REQUIRED
                state.value.access != MessageAccess.READY -> MessageReplyResult.UNAVAILABLE
                before == null || entries[id]?.signature != before.signature || id in spent -> MessageReplyResult.STALE
                text.isBlank() || text.length > MAX_REPLY -> MessageReplyResult.INVALID_TEXT
                !valid() -> MessageReplyResult.STALE
                !allowLocked && keyguard?.isDeviceLocked != false -> MessageReplyResult.LOCKED
                before.action == null -> MessageReplyResult.UNSUPPORTED
                else -> {
                    val dispatched = dispatchReply(context, before.action, text, spent, id)
                    if (id in spent) mutableState.value = mutableState.value.copy(messages = mutableState.value.messages.map {
                        if (it.id == id) it.copy(canReply = false) else it
                    })
                    dispatched
                }
            }
        } catch (_: SecurityException) { clear(MessageAccess.ACCESS_REQUIRED); MessageReplyResult.ACCESS_REQUIRED }
        catch (_: RuntimeException) { clear(MessageAccess.UNAVAILABLE); MessageReplyResult.UNAVAILABLE }
        val code = when (result) {
            MessageReplyResult.DISPATCHED -> ErrorCode.NONE
            MessageReplyResult.STALE -> ErrorCode.ABORTED
            MessageReplyResult.UNSUPPORTED -> ErrorCode.UNSUPPORTED
            MessageReplyResult.ACCESS_REQUIRED -> ErrorCode.PERMISSION_DENIED
            MessageReplyResult.UNAVAILABLE -> ErrorCode.UNAVAILABLE
            MessageReplyResult.INVALID_TEXT -> ErrorCode.INVALID_ARGUMENT
            MessageReplyResult.LOCKED -> ErrorCode.FAILED_PRECONDITION
        }
        journal?.record(Component.NOTIFICATION, operation, Event.REQUEST,
            if (result == MessageReplyResult.DISPATCHED) Outcome.OBSERVED else Outcome.FAILED, code)
        return result
    }

    private fun appLabel(packageName: String): String = try {
        bounded(context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString())
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { bounded(packageName) }
      catch (_: RuntimeException) { bounded(packageName) }

    private fun hasAccess(): Boolean = try { manager?.isNotificationListenerAccessGranted(component) == true }
        catch (_: RuntimeException) { false }

    private fun rebind() {
        try { MediaAccessService.rebindIfDisconnected(component) }
        catch (_: RuntimeException) { /* Listener remains unavailable until Android connects it. */ }
    }

    private fun clear(access: MessageAccess) {
        entries = emptyMap()
        spent.clear()
        mutableState.value = MessageInboxState(access)
    }

    override fun close() {
        mainThread()
        if (closed) return
        closed = true
        running = false
        scope.cancel()
        clear(MessageAccess.UNAVAILABLE)
    }

    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

internal data class ReplyAction(val pendingIntent: PendingIntent, val remoteInput: RemoteInput, val contract: List<String>)
internal data class MessageSignature(
    val key: String, val packageName: String, val postTime: Long, val whenTime: Long,
    val title: String, val text: String, val content: List<String>, val actionContract: List<String>,
    val pendingIntent: PendingIntent?,
)
internal data class MessageSource(
    val signature: MessageSignature, val packageName: String, val title: String, val text: String,
    val timestamp: Long, val action: ReplyAction?, val id: String = "",
    val sender: String? = null,
)

/** Native pending identity; independent of ephemeral action IDs, posting time and action updates. */
internal fun messageRecoveryId(source: MessageSource): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (field in listOf(source.packageName, source.signature.key, source.title,
        source.sender.orEmpty(), source.text) + source.signature.content) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** A removed or changed notification loses its opaque page-local reply ID. */
internal fun reconcileMessages(previous: Map<String, MessageSource>, notifications: List<StatusBarNotification>): Map<String, MessageSource> {
    val prior = previous.values.associateBy { it.signature }
    val next = linkedMapOf<String, MessageSource>()
    val duplicateKeys = notifications.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
    for (sbn in notifications.sortedByDescending { it.postTime }) {
        if (sbn.key in duplicateKeys) continue
        val parsed = parseMessage(sbn) ?: continue
        val source = parsed.copy(id = prior[parsed.signature]?.id ?: UUID.randomUUID().toString())
        next[source.id] = source
        if (next.size == MAX_MESSAGES) break
    }
    return next
}

/** Public notification fields only. No package heuristics and no fallback from malformed MessagingStyle. */
internal fun parseMessage(sbn: StatusBarNotification): MessageSource? = try {
    val n = sbn.notification
    if (sbn.user != Process.myUserHandle() || n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0 ||
        n.category == Notification.CATEGORY_CALL) return null
    val extras = n.extras
    val messaging = extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
    val discord = sbn.packageName == "com.discord"
    val content: List<String>
    val title: String
    val text: String
    val sender: String?
    val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
    if (messaging || (discord && !raw.isNullOrEmpty())) {
        if (raw.isNullOrEmpty() || raw.size > MAX_MESSAGES || raw.any { it !is Bundle }) return null
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
        if (messages.size != raw.size || messages.any {
                it.text.isNullOrBlank() || it.text!!.length > MAX_SOURCE_TEXT ||
                    (it.sender?.length ?: 0) > MAX_SOURCE_TEXT ||
                    (it.senderPerson?.name?.length ?: 0) > MAX_SOURCE_TEXT
            }) return null
        val last = messages.last()
        val senderName = (last.senderPerson?.name ?: last.sender)?.toString()?.trim().orEmpty()
        sender = senderName.takeIf { it.isNotBlank() }?.let(::bounded)
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val ordinaryTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        title = if (discord) conversation.ifBlank { senderName.ifBlank { ordinaryTitle.ifBlank { "Discord" } } }
            else conversation.ifBlank { ordinaryTitle }
        val body = last.text.toString().trim()
        text = if (discord && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) && senderName.isNotBlank())
            "$senderName: $body" else body
        content = messages.flatMap { listOf(it.text.toString(),
            (it.senderPerson?.name ?: it.sender)?.toString().orEmpty(), it.timestamp.toString()) } +
            listOf(conversation, ordinaryTitle, extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION).toString())
    } else {
        sender = null // Ordinary title and conversation label do not prove a sender identity.
        if (!discord && n.category != Notification.CATEGORY_MESSAGE) return null
        val ordinaryTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val ordinaryText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (discord) {
            val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            val rawLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if ((rawLines?.size ?: 0) > MAX_MESSAGES) return null
            val lines = rawLines?.map { it.toString().trim() }.orEmpty()
            if (lines.any { it.length > MAX_SOURCE_TEXT } ||
                big.length > MAX_SOURCE_TEXT || conversation.length > MAX_SOURCE_TEXT) return null
            title = conversation.ifBlank { ordinaryTitle.ifBlank { "Discord" } }
            text = big.ifBlank { ordinaryText.ifBlank { lines.lastOrNull().orEmpty() } }
            content = listOf(conversation, ordinaryTitle, ordinaryText, big) + lines
        } else {
            title = ordinaryTitle
            text = ordinaryText
            content = listOf(title, text)
        }
    }
    if (title.isBlank() || text.isBlank() || title.length > MAX_SOURCE_TEXT || text.length > MAX_SOURCE_TEXT ||
        content.any { it.length > MAX_SOURCE_TEXT }) return null
    val action = selectReplyAction(sbn)
    val actionContract = action?.contract.orEmpty()
    val signature = MessageSignature(sbn.key, sbn.packageName, sbn.postTime, n.`when`,
        title, text, content, actionContract, action?.pendingIntent)
    MessageSource(signature, sbn.packageName, bounded(title), bounded(text),
        if (n.`when` > 0) n.`when` else sbn.postTime, action, sender = sender)
} catch (_: RuntimeException) { null }

/** Prefer one semantic REPLY action; an ambiguous set cannot authorize a guess. */
internal fun selectReplyAction(sbn: StatusBarNotification): ReplyAction? {
    val actions = sbn.notification.actions.orEmpty()
    if (actions.size > 16) return null
    val candidates = actions.mapNotNull { action ->
        if (action.semanticAction != Notification.Action.SEMANTIC_ACTION_NONE &&
            action.semanticAction != Notification.Action.SEMANTIC_ACTION_REPLY) return@mapNotNull null
        val pending = action.actionIntent ?: return@mapNotNull null
        if (pending.creatorPackage != sbn.packageName || (Build.VERSION.SDK_INT >= 31 && pending.isImmutable)) return@mapNotNull null
        val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput && it.resultKey.isNotBlank() }
        if (inputs.size != 1) return@mapNotNull null
        val input = inputs.single()
        if (action.title.length > MAX_TEXT || input.resultKey.length > MAX_TEXT || (input.label?.length ?: 0) > MAX_TEXT ||
            (input.choices?.size ?: 0) > 16 || input.choices.orEmpty().any { it.length > MAX_TEXT }) return@mapNotNull null
        val allowedTypes = input.allowedDataTypes.orEmpty()
        if (allowedTypes.size > 16 || allowedTypes.any { it.length > MAX_TEXT }) return@mapNotNull null
        val contract = listOf(action.semanticAction.toString(), action.title.toString(),
            input.resultKey, input.label?.toString().orEmpty(), input.choices?.joinToString("\u0000").orEmpty(),
            input.allowFreeFormInput.toString(), input.editChoicesBeforeSending.toString(),
            allowedTypes.sorted().joinToString("\u0000"))
        if (contract.any { it.length > MAX_SOURCE_TEXT }) return@mapNotNull null
        Triple(action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY, pending,
            ReplyAction(pending, input, contract))
    }
    val semantic = candidates.filter { it.first }
    val chosen = when {
        semantic.size == 1 -> semantic.single()
        semantic.isNotEmpty() -> null
        candidates.size == 1 -> candidates.single()
        else -> null
    } ?: return null
    return chosen.third
}

internal fun dispatchReply(context: Context, action: ReplyAction, text: String,
    spent: MutableSet<String>, id: String): MessageReplyResult {
    if (!spent.add(id)) return MessageReplyResult.STALE // Consume before invoking the app's PendingIntent.
    return try {
        val intent = Intent()
        RemoteInput.addResultsToIntent(arrayOf(action.remoteInput), intent,
            Bundle().apply { putCharSequence(action.remoteInput.resultKey, text) })
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        action.pendingIntent.send(context, 0, intent)
        MessageReplyResult.DISPATCHED // Request submitted to the app; delivery is not observable here.
    } catch (_: PendingIntent.CanceledException) { MessageReplyResult.STALE }
      catch (_: SecurityException) { MessageReplyResult.UNAVAILABLE }
      catch (_: RuntimeException) { MessageReplyResult.UNAVAILABLE }
}

private fun bounded(value: String): String = value.substring(0,
    value.offsetByCodePoints(0, minOf(MAX_TEXT, value.codePointCount(0, value.length))))
