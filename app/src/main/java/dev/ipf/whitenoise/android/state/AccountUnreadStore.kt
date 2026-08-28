package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class VersionedAccountUnreadValue(
    val value: AccountUnreadValue,
    val revision: Long?,
)

internal data class AccountUnreadPublication(
    val values: Map<String, VersionedAccountUnreadValue>,
    val writtenRefs: Set<String>,
)

/** Owns synchronized unread freshness, revisions, and observable publication. */
internal class AccountUnreadStore {
    private val lock = Any()
    private var revision = 0L
    private val revisions = mutableMapOf<String, Long>()
    private var refreshGeneration = 0L

    var values by mutableStateOf<Map<String, AccountUnreadValue>>(emptyMap())
        private set

    val retainedCounts: Map<String, ULong>
        get() = values.mapValues { (_, value) -> value.unreadCount }

    val manualUnreadRefs: Set<String>
        get() = values.filterValues { it.hasManualUnread == true }.keys

    fun updateCount(
        accountRef: String?,
        unreadCount: ULong,
    ) {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
        updateValue(ref) { previous ->
            AccountUnreadValue(
                unreadCount = unreadCount,
                freshness = AccountUnreadFreshness.CONFIRMED,
                hasManualUnread = previous?.hasManualUnread,
            )
        }
    }

    fun updateProjection(
        accountRef: String?,
        unreadCount: ULong,
        hasManualUnread: Boolean,
    ) {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
        updateValue(ref) {
            AccountUnreadValue(unreadCount, AccountUnreadFreshness.CONFIRMED, hasManualUnread)
        }
    }

    fun updateManualUnread(
        accountRef: String?,
        hasManualUnread: Boolean,
    ) {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
        updateValue(ref) { previous ->
            AccountUnreadValue(
                unreadCount = previous?.unreadCount ?: 0uL,
                freshness = previous?.freshness ?: AccountUnreadFreshness.UNKNOWN,
                hasManualUnread = hasManualUnread,
            )
        }
    }

    fun updateValue(
        accountRef: String,
        transform: (AccountUnreadValue?) -> AccountUnreadValue,
    ) {
        synchronized(lock) {
            val previous = values[accountRef]
            val next = transform(previous)
            revision += 1L
            revisions[accountRef] = revision
            if (previous != next) values = values + (accountRef to next)
        }
    }

    fun snapshot(): Map<String, VersionedAccountUnreadValue> = synchronized(lock) { snapshotLocked() }

    fun markUnknown(accountRef: String) {
        synchronized(lock) {
            // Cancel older bulk publications while allowing an already-running
            // exact fold with the same per-account revision to replace unknown.
            refreshGeneration += 1L
            val previous = values[accountRef]
            val unknown =
                AccountUnreadValue(
                    unreadCount = previous?.unreadCount ?: 0uL,
                    freshness = AccountUnreadFreshness.UNKNOWN,
                    hasManualUnread = previous?.hasManualUnread,
                )
            if (previous != unknown) values = values + (accountRef to unknown)
        }
    }

    fun publishRefresh(
        previous: Map<String, VersionedAccountUnreadValue>,
        refreshed: Map<String, AccountUnreadValue>,
        generation: Long,
    ): AccountUnreadPublication =
        synchronized(lock) {
            if (refreshGeneration != generation) {
                return@synchronized AccountUnreadPublication(snapshotLocked(), emptySet())
            }
            val current = snapshotLocked()
            val merged = linkedMapOf<String, VersionedAccountUnreadValue>()
            val writtenRefs = mutableSetOf<String>()
            refreshed.forEach { (ref, value) ->
                val currentValue = current[ref]
                if (currentValue?.revision != previous[ref]?.revision) {
                    currentValue?.let { merged[ref] = it }
                } else {
                    revision += 1L
                    merged[ref] = VersionedAccountUnreadValue(value, revision)
                    writtenRefs += ref
                }
            }
            // Preserve accounts added by a newer list refresh, while allowing
            // accounts absent from this authoritative pass to be removed.
            current.forEach { (ref, value) ->
                if (ref !in previous && ref !in merged) merged[ref] = value
            }
            val mergedValues = merged.mapValues { (_, versioned) -> versioned.value }
            if (values != mergedValues) values = mergedValues
            revisions.clear()
            merged.forEach { (ref, versioned) ->
                versioned.revision?.let { revisions[ref] = it }
            }
            AccountUnreadPublication(merged, writtenRefs)
        }

    fun beginRefresh(): Long =
        synchronized(lock) {
            refreshGeneration += 1L
            refreshGeneration
        }

    fun isRefreshCurrent(generation: Long): Boolean = synchronized(lock) { refreshGeneration == generation }

    fun publishExactIfUnchanged(
        accountRef: String,
        previous: VersionedAccountUnreadValue?,
        value: AccountUnreadValue,
    ) {
        synchronized(lock) {
            if (revisions[accountRef] != previous?.revision) return
            revision += 1L
            revisions[accountRef] = revision
            if (values[accountRef] != value) values = values + (accountRef to value)
        }
    }

    private fun snapshotLocked(): Map<String, VersionedAccountUnreadValue> =
        values.mapValues { (ref, value) -> VersionedAccountUnreadValue(value, revisions[ref]) }
}

internal fun accountUnreadExactBaseline(
    original: Map<String, VersionedAccountUnreadValue>,
    interim: AccountUnreadPublication,
): Map<String, VersionedAccountUnreadValue> =
    interim.values.toMutableMap().apply {
        interim.values.keys.forEach { ref ->
            if (ref !in interim.writtenRefs) {
                original[ref]?.let { this[ref] = it } ?: remove(ref)
            }
        }
    }
