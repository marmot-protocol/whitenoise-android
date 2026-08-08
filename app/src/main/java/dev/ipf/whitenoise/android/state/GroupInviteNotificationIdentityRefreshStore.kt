package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

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
        val update: NotificationUpdateFfi,
        val resolvedName: String?,
    )

    private data class Entry(
        val update: NotificationUpdateFfi,
        val displayedName: String?,
        val desiredName: String?,
    )

    private val lock = Any()
    private val entriesByNotificationKey = linkedMapOf<String, Entry>()
    private val refreshesInFlight = mutableSetOf<String>()

    fun rememberPosted(
        update: NotificationUpdateFfi,
        displayedName: String?,
    ) {
        if (
            update.trigger != NotificationTriggerFfi.GROUP_INVITE ||
            update.notificationKey.isBlank() ||
            update.sender.accountIdHex.isBlank()
        ) {
            return
        }
        synchronized(lock) {
            entriesByNotificationKey.remove(update.notificationKey)
            entriesByNotificationKey[update.notificationKey] =
                Entry(
                    update = update,
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
                if (!entry.update.sender.accountIdHex
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
        update: NotificationUpdateFfi,
        displayedName: String?,
        contentRedacted: Boolean,
    ): RefreshCandidate? =
        synchronized(lock) {
            refreshesInFlight.remove(update.notificationKey)
            val current = entriesByNotificationKey[update.notificationKey] ?: return@synchronized null
            if (contentRedacted) return@synchronized null
            val refreshedEntry =
                current.copy(
                    displayedName = displayedName,
                )
            entriesByNotificationKey[update.notificationKey] = refreshedEntry
            claimIfPending(update.notificationKey, refreshedEntry)
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
            update = entry.update,
            resolvedName = entry.desiredName,
        )
    }
}
