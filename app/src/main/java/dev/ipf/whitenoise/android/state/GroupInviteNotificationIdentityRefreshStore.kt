package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

/**
 * Bounded process-local record of posted invite cards whose sender identity was
 * incomplete. Invite notifications have a unique notification key, so they can
 * be refreshed safely without overwriting a newer accumulating message card.
 */
internal class GroupInviteNotificationIdentityRefreshStore(
    private val maxEntries: Int = 64,
) {
    private data class Entry(
        val update: NotificationUpdateFfi,
        val displayedName: String?,
        val displayedAvatarUrl: String?,
    )

    private val lock = Any()
    private val entriesByNotificationKey = linkedMapOf<String, Entry>()
    private val refreshesInFlight = mutableSetOf<String>()

    fun rememberPosted(
        update: NotificationUpdateFfi,
        displayedName: String?,
        displayedAvatarUrl: String?,
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
                Entry(update, displayedName, displayedAvatarUrl)
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
        resolvedAvatarUrl: String?,
    ): List<NotificationUpdateFfi> {
        if (senderAccountIdHex.isBlank() || (resolvedName == null && resolvedAvatarUrl == null)) return emptyList()
        return synchronized(lock) {
            val candidates =
                entriesByNotificationKey.values
                    .filter { entry ->
                        entry.update.notificationKey !in refreshesInFlight &&
                            entry.update.sender.accountIdHex
                                .equals(senderAccountIdHex, ignoreCase = true) &&
                            (
                                resolvedName?.let { it != entry.displayedName } == true ||
                                    resolvedAvatarUrl?.let { it != entry.displayedAvatarUrl } == true
                            )
                    }.map(Entry::update)
            refreshesInFlight += candidates.map(NotificationUpdateFfi::notificationKey)
            candidates
        }
    }

    fun markRefreshed(
        update: NotificationUpdateFfi,
        displayedName: String?,
        displayedAvatarUrl: String?,
    ) {
        synchronized(lock) {
            refreshesInFlight.remove(update.notificationKey)
            val current = entriesByNotificationKey[update.notificationKey] ?: return
            entriesByNotificationKey[update.notificationKey] =
                current.copy(
                    displayedName = displayedName ?: current.displayedName,
                    displayedAvatarUrl = displayedAvatarUrl ?: current.displayedAvatarUrl,
                )
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
}
