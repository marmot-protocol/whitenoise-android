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
    /** One claimed invite refresh carrying the original post's staleness and write budget. */
    internal data class RefreshCandidate(
        val identity: GroupInviteNotificationIdentity,
        val resolvedName: String?,
        val postEpoch: Long,
        val accountCacheEpoch: Long,
        val engineMuted: Boolean,
        val lateCorrectionPermit: NotificationLateCorrectionPermit,
    )

    private data class Entry(
        val identity: GroupInviteNotificationIdentity,
        val displayedName: String?,
        val desiredName: String?,
        val postEpoch: Long,
        val accountCacheEpoch: Long,
        val engineMuted: Boolean,
        val lateCorrectionPermit: NotificationLateCorrectionPermit,
    )

    private val lock = Any()
    private val entriesByNotificationKey = linkedMapOf<String, Entry>()
    private val refreshesInFlight = mutableMapOf<String, NotificationLateCorrectionPermit>()

    /** Remembers the rendered invite identity and its shared late-correction ownership. */
    fun rememberPosted(
        identity: GroupInviteNotificationIdentity,
        displayedName: String?,
        postEpoch: Long = 0L,
        accountCacheEpoch: Long = 0L,
        engineMuted: Boolean = false,
        lateCorrectionPermit: NotificationLateCorrectionPermit = NotificationLateCorrectionPermit(),
    ) {
        if (identity.notificationKey.isBlank() || identity.senderAccountIdHex.isBlank()) return
        synchronized(lock) {
            entriesByNotificationKey.remove(identity.notificationKey)
            refreshesInFlight.remove(identity.notificationKey)
            entriesByNotificationKey[identity.notificationKey] =
                Entry(
                    identity = identity,
                    displayedName = displayedName,
                    desiredName = displayedName,
                    postEpoch = postEpoch,
                    accountCacheEpoch = accountCacheEpoch,
                    engineMuted = engineMuted,
                    lateCorrectionPermit = lateCorrectionPermit,
                )
            while (entriesByNotificationKey.size > maxEntries) {
                val evictedKey = entriesByNotificationKey.keys.first()
                entriesByNotificationKey.remove(evictedKey)
                refreshesInFlight.remove(evictedKey)
            }
        }
    }

    /** Claims active invites whose rendered sender differs from [resolvedName]. */
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

    /** Claims pending names, primarily when app-lock deferral ends. */
    fun claimPendingRefreshes(): List<RefreshCandidate> =
        synchronized(lock) {
            entriesByNotificationKey.mapNotNull { (notificationKey, entry) ->
                claimIfPending(notificationKey, entry)
            }
        }

    /** Releases [candidate]'s claim without disturbing a newer same-key post. */
    fun release(candidate: RefreshCandidate) {
        synchronized(lock) {
            if (refreshesInFlight[candidate.identity.notificationKey] === candidate.lateCorrectionPermit) {
                refreshesInFlight.remove(candidate.identity.notificationKey)
            }
        }
    }

    /** Ensures exceptional/cancelled work cannot strand the store claim. */
    suspend fun <T> runClaimedRefresh(
        candidate: RefreshCandidate,
        block: suspend () -> T,
    ): T {
        var completedNormally = false
        try {
            val result = block()
            completedNormally = true
            return result
        } finally {
            if (!completedNormally) release(candidate)
        }
    }

    /** Forgets [candidate] only if no newer same-key invite has replaced it. */
    fun forget(candidate: RefreshCandidate) {
        synchronized(lock) {
            val notificationKey = candidate.identity.notificationKey
            if (entriesByNotificationKey[notificationKey]?.lateCorrectionPermit === candidate.lateCorrectionPermit) {
                entriesByNotificationKey.remove(notificationKey)
            }
            if (refreshesInFlight[notificationKey] === candidate.lateCorrectionPermit) {
                refreshesInFlight.remove(notificationKey)
            }
        }
    }

    /** Returns true only while [candidate] still owns the latest post for its notification key. */
    fun isCurrent(candidate: RefreshCandidate): Boolean =
        synchronized(lock) {
            entriesByNotificationKey[candidate.identity.notificationKey]
                ?.lateCorrectionPermit === candidate.lateCorrectionPermit
        }

    /** Drops every retained invite identity at an account-cache lifetime boundary. */
    fun clear() {
        synchronized(lock) {
            entriesByNotificationKey.clear()
            refreshesInFlight.clear()
        }
    }

    /** Atomically claims one changed-name entry while preserving its original post budget. */
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
        refreshesInFlight[notificationKey] = entry.lateCorrectionPermit
        return RefreshCandidate(
            identity = entry.identity,
            resolvedName = entry.desiredName,
            postEpoch = entry.postEpoch,
            accountCacheEpoch = entry.accountCacheEpoch,
            engineMuted = entry.engineMuted,
            lateCorrectionPermit = entry.lateCorrectionPermit,
        )
    }
}
