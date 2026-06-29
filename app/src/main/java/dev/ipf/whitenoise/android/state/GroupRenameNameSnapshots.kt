package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.GroupRenamePreviousName
import dev.ipf.whitenoise.android.core.ProfileSanitizer

/**
 * Process-lifetime, non-persistent memory of group names seen by this client.
 *
 * This is the local-snapshot route for group rename copy: when a live group
 * projection moves from A to B, the just-observed A can annotate the matching
 * kind-1210 row even though today's Marmot FFI only exposes `data.name`. It is
 * deliberately short-lived UI/runtime state, not an Android-owned source of
 * truth for protocol data (AGENTS.md): empty on fresh process/install, bounded,
 * and safe to miss.
 */
internal class GroupRenameNameSnapshots(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val pendingTtlMillis: Long = DEFAULT_PENDING_TTL_MILLIS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private data class GroupKey(
        val accountRef: String,
        val groupIdHex: String,
    )

    private data class RenameKey(
        val group: GroupKey,
        val newDisplayName: String,
        val sequence: Long,
    )

    private data class EventKey(
        val group: GroupKey,
        val eventId: String,
    )

    private data class PendingPreviousName(
        val previousName: GroupRenamePreviousName,
        val observedAtMs: Long,
        val sequence: Long,
    )

    private val lock = Any()
    private var nextSequence = 0L

    private val currentNames =
        object : LinkedHashMap<GroupKey, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GroupKey, String>?): Boolean = size > maxEntries
        }

    private val pendingPreviousNames =
        object : LinkedHashMap<RenameKey, PendingPreviousName>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RenameKey, PendingPreviousName>?): Boolean = size > maxEntries
        }

    private val assignedPreviousNames =
        object : LinkedHashMap<EventKey, PendingPreviousName>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EventKey, PendingPreviousName>?): Boolean = size > maxEntries
        }

    fun remember(
        accountRef: String?,
        groupIdHex: String?,
        name: String?,
    ) {
        val key = groupKey(accountRef, groupIdHex) ?: return
        val observedName = name ?: return
        synchronized(lock) {
            if (!currentNames.containsKey(key)) {
                currentNames[key] = observedName
            }
        }
    }

    fun setCurrent(
        accountRef: String?,
        groupIdHex: String?,
        name: String?,
    ) {
        val key = groupKey(accountRef, groupIdHex) ?: return
        val observedName = name ?: return
        synchronized(lock) {
            currentNames[key] = observedName
        }
    }

    fun record(
        accountRef: String?,
        groupIdHex: String?,
        name: String,
    ): GroupRenamePreviousName? {
        val key = groupKey(accountRef, groupIdHex) ?: return null
        val now = nowMillis()
        synchronized(lock) {
            pruneExpiredLocked(now)
            val previous = currentNames.put(key, name)
            val recorded =
                if (previous != null && !sameDisplayName(previous, name)) {
                    val newDisplayName = displayName(name)
                    if (newDisplayName != null) {
                        val sequence = nextSequence++
                        val pending = PendingPreviousName(GroupRenamePreviousName(previous), now, sequence)
                        pendingPreviousNames[RenameKey(key, newDisplayName, sequence)] = pending
                        pending.previousName
                    } else {
                        null
                    }
                } else {
                    null
                }
            return recorded ?: previousForLocked(key, name, now)
        }
    }

    fun previousFor(
        accountRef: String?,
        groupIdHex: String?,
        newName: String?,
        eventId: String? = null,
    ): GroupRenamePreviousName? {
        val key = groupKey(accountRef, groupIdHex) ?: return null
        val now = nowMillis()
        synchronized(lock) {
            pruneExpiredLocked(now)
            return previousForLocked(key, newName, now, eventId)
        }
    }

    private fun previousForLocked(
        key: GroupKey,
        newName: String?,
        now: Long,
        eventId: String? = null,
    ): GroupRenamePreviousName? {
        val newDisplayName = displayName(newName) ?: return null
        val normalizedEventId = eventId?.takeIf { it.isNotBlank() }
        if (normalizedEventId != null) {
            val eventKey = EventKey(key, normalizedEventId)
            assignedPreviousNames[eventKey]?.let { assigned ->
                return if (now - assigned.observedAtMs <= pendingTtlMillis) {
                    assigned.previousName
                } else {
                    assignedPreviousNames.remove(eventKey)
                    null
                }
            }
        }
        var candidate: MutableMap.MutableEntry<RenameKey, PendingPreviousName>? = null
        for (entry in pendingPreviousNames.entries) {
            val renameKey = entry.key
            val pending = entry.value
            val matches =
                renameKey.group == key &&
                    renameKey.newDisplayName == newDisplayName &&
                    now - pending.observedAtMs <= pendingTtlMillis
            if (matches && (candidate == null || pending.sequence < candidate.value.sequence)) {
                candidate = entry
            }
        }
        val matched = candidate ?: return null
        if (normalizedEventId != null) {
            pendingPreviousNames.remove(matched.key)
            assignedPreviousNames[EventKey(key, normalizedEventId)] = matched.value
        }
        return matched.value.previousName
    }

    private fun pruneExpiredLocked(now: Long) {
        val pendingIterator = pendingPreviousNames.entries.iterator()
        while (pendingIterator.hasNext()) {
            val pending = pendingIterator.next().value
            if (now - pending.observedAtMs > pendingTtlMillis) {
                pendingIterator.remove()
            }
        }
        val assignedIterator = assignedPreviousNames.entries.iterator()
        while (assignedIterator.hasNext()) {
            val assigned = assignedIterator.next().value
            if (now - assigned.observedAtMs > pendingTtlMillis) {
                assignedIterator.remove()
            }
        }
    }

    private fun groupKey(
        accountRef: String?,
        groupIdHex: String?,
    ): GroupKey? {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
        val group = groupIdHex?.takeIf { it.isNotBlank() } ?: return null
        return GroupKey(account, group)
    }

    private fun sameDisplayName(
        left: String?,
        right: String?,
    ): Boolean = displayName(left) == displayName(right)

    private fun displayName(name: String?): String? = ProfileSanitizer.displayName(name)

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 256
        const val DEFAULT_PENDING_TTL_MILLIS = 30L * 60L * 1000L
    }
}
