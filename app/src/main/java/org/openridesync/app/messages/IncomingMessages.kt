package org.openridesync.app.messages

import org.openridesync.app.runtime.IncomingMessageMode
import org.openridesync.app.runtime.MessageReadStatus

/** One ride's FIFO. Only pending fingerprints cross lifetimes; notification content never does. */
internal class IncomingMessages(initialPending: Set<String>?, private val save: (Set<String>) -> Boolean) {
    enum class Phase { PROMPT, READ }
    data class Entry(val item: MessageItem, val mode: IncomingMessageMode,
        val phase: Phase = Phase.PROMPT, val status: MessageReadStatus = MessageReadStatus.NONE)
    private var pending = initialPending.orEmpty().toSet()
    private var baseline = false
    private var seen = emptySet<String>()
    private var mode = IncomingMessageMode.IGNORE
    private val queue = mutableListOf<Entry>()
    private val interrupted = mutableMapOf<String, Pair<Phase, MessageReadStatus>>()
    var available = initialPending != null && initialPending.size <= MessageRecoveryStore.LIMIT
        private set
    val front get() = queue.firstOrNull()

    fun sync(inbox: MessageInboxState, setting: IncomingMessageMode) {
        if (inbox.access != MessageAccess.READY) { suspendContent(); return }
        if (setting != mode) {
            queue.clear(); interrupted.clear(); seen = emptySet(); baseline = false
        }
        mode = setting
        val duplicate = inbox.messages.groupingBy { it.recoveryId }.eachCount().filterValues { it > 1 }.keys
        val current = inbox.messages.filter { it.recoveryId !in duplicate }.take(MessageRecoveryStore.LIMIT)
            .associateBy { it.recoveryId }
        val keys = current.keys.toSet()
        interrupted.keys.retainAll(keys)
        val recovered = !baseline
        val additions = if (recovered) current.values.filter { it.recoveryId in pending }
            else current.values.filter { it.recoveryId !in seen }
        seen = keys
        baseline = true
        if (!available) return
        val nextPending = pending.intersect(keys) +
            additions.filter { setting != IncomingMessageMode.IGNORE }.map { it.recoveryId }
        if (!persist(nextPending)) return
        if (setting == IncomingMessageMode.IGNORE) { queue.clear(); return }
        // A source replacement invalidates its old opaque ID without authorizing new speech.
        val retained = queue.mapNotNull { entry -> current[entry.item.recoveryId]?.let { item ->
            if (item.id == entry.item.id) entry.copy(item = item)
            else entry.copy(item = item, status = if (entry.status == MessageReadStatus.READING)
                MessageReadStatus.FAILED else entry.status)
        } }
        queue.clear(); queue.addAll(retained)
        val queued = queue.map { it.item.recoveryId }.toSet()
        val resumedOrder = interrupted.keys.withIndex().associate { it.value to it.index }
        additions.sortedWith(compareBy<MessageItem> { resumedOrder[it.recoveryId] ?: Int.MAX_VALUE }
            .thenBy { it.postedAt }).filter { it.recoveryId !in queued }.forEach {
            val prior = interrupted.remove(it.recoveryId)
            queue.add(Entry(it, setting, prior?.first ?: Phase.PROMPT, prior?.second ?: MessageReadStatus.NONE))
        }
    }

    fun update(phase: Phase, status: MessageReadStatus) {
        if (queue.isNotEmpty()) queue[0] = queue[0].copy(phase = phase, status = status)
    }

    fun interruptSpeech() {
        front?.takeIf { it.status == MessageReadStatus.READING }?.let { update(it.phase, MessageReadStatus.FAILED) }
    }

    fun yield(forget: Boolean) {
        val entry = front ?: return
        if (forget && !persist(pending - entry.item.recoveryId)) return
        queue.removeAt(0)
    }

    fun home() { if (persist(emptySet())) queue.clear() }

    fun suspendContent() {
        queue.forEach {
            interrupted[it.item.recoveryId] = it.phase to
                if (it.status == MessageReadStatus.READING || it.phase == Phase.READ && it.status == MessageReadStatus.NONE)
                    MessageReadStatus.FAILED else it.status
        }
        queue.clear(); seen = emptySet(); baseline = false
    }

    private fun persist(value: Set<String>): Boolean {
        if (!available) return false
        if (value == pending) return true
        val success = try { save(value.toSet()) } catch (_: RuntimeException) { false }
        if (!success) { available = false; queue.clear(); return false }
        pending = value.toSet()
        interrupted.keys.retainAll(pending)
        return true
    }
}
