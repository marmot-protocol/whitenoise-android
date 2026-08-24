package dev.ipf.whitenoise.android.state

/**
 * Bounded process-local record of posted invite cards whose sender name was
 * incomplete. Invite cards use a plain notification style that does not render
 * sender avatars, so only the displayed name participates in refresh state.
 * Invites have a unique notification key and can therefore be refreshed safely
 * without overwriting a newer accumulating message card.
 */
internal class GroupInviteNotificationIdentityRefreshStore(
    private val maxEntries: Int = 64,
) {
    internal data class RefreshCandidate(
        val identity: GroupInviteNotificationIdentity,
        val resolvedName: String?,
    )

    private data class Entry(
        val identity: GroupInviteNotificationIdentity,
        val displayedName: String?,
        val desiredName: String?,
    )

    private val lock = Any()
    private val entriesByNotificationKey = linkedMapOf<String, Entry>()
    private val refreshesInFlight = mutableSetOf<String>()

    fun rememberPosted(
        identity: GroupInviteNotificationIdentity,
        displayedName: String?,
    ) {
        if (identity.notificationKey.isBlank() || identity.senderAccountIdHex.isBlank()) return
        synchronized(lock) {
            entriesByNotificationKey.remove(identity.notificationKey)
            entriesByNotificationKey[identity.notificationKey] =
                Entry(
                    identity = identity,
                    displayedName = displayedName,
                    desiredName = displayedName,
                )
            while (entriesByNotificationKey.size > maxEntries) {
                val evictedKey = entriesByNotificationKey.keys.first()
                entriesByNotificationKey.remove(evictedKey)
                refreshesInFlight.remove(evictedKey)
            }
        }
    }

    fun refreshCandidates(
        senderAccountIdHex: String,
        resolvedName: String?,
    ): List<RefreshCandidate> {
        if (senderAccountIdHex.isBlank() || resolvedName == null) return emptyList()
        return synchronized(lock) {
            val candidates = mutableListOf<RefreshCandidate>()
            entriesByNotificationKey.entries.forEach { (notificationKey, entry) ->
                if (!entry.identity.senderAccountIdHex
                        .equals(senderAccountIdHex, ignoreCase = true)
                ) {
                    return@forEach
                }
                val updatedEntry =
                    entry.copy(
                        desiredName = resolvedName,
                    )
                entriesByNotificationKey[notificationKey] = updatedEntry
                claimIfPending(notificationKey, updatedEntry)?.let(candidates::add)
            }
            candidates
        }
    }

    fun completeRefresh(
        notificationKey: String,
        displayedName: String?,
        contentRedacted: Boolean,
    ): RefreshCandidate? =
        synchronized(lock) {
            refreshesInFlight.remove(notificationKey)
            val current = entriesByNotificationKey[notificationKey] ?: return@synchronized null
            if (contentRedacted) return@synchronized null
            val refreshedEntry =
                current.copy(
                    displayedName = displayedName,
                )
            entriesByNotificationKey[notificationKey] = refreshedEntry
            claimIfPending(notificationKey, refreshedEntry)
        }

    fun claimPendingRefreshes(): List<RefreshCandidate> =
        synchronized(lock) {
            entriesByNotificationKey.mapNotNull { (notificationKey, entry) ->
                claimIfPending(notificationKey, entry)
            }
        }

    fun release(notificationKey: String) {
        synchronized(lock) {
            refreshesInFlight.remove(notificationKey)
        }
    }

    suspend fun <T> runClaimedRefresh(
        notificationKey: String,
        block: suspend () -> T,
    ): T {
        var completedNormally = false
        try {
            val result = block()
            completedNormally = true
            return result
        } finally {
            if (!completedNormally) release(notificationKey)
        }
    }

    fun forget(notificationKey: String) {
        synchronized(lock) {
            entriesByNotificationKey.remove(notificationKey)
            refreshesInFlight.remove(notificationKey)
        }
    }

    private fun claimIfPending(
        notificationKey: String,
        entry: Entry,
    ): RefreshCandidate? {
        if (
            notificationKey in refreshesInFlight ||
            entry.desiredName == entry.displayedName
        ) {
            return null
        }
        refreshesInFlight += notificationKey
        return RefreshCandidate(
            identity = entry.identity,
            resolvedName = entry.desiredName,
        )
    }
}
