package dev.ipf.whitenoise.android.notifications

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val PIN_SUFFIX_MARKER = "-pin-"
private const val REACTION_SUFFIX_MARKER = "-reaction"

/**
 * Tracks destructive channel-cleanup epochs under [UserEventNotificationGroup.mutationLock] so
 * in-flight channel writes that waited behind account sign-out, wipe, or local conversation
 * deletion cannot recreate a deleted private channel once they acquire the lock.
 */
internal object NotificationChannelWriteGuard {
    private val epoch = AtomicLong(0L)
    private val accountClearedAt = ConcurrentHashMap<String, Long>()
    private val shortcutClearedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var allClearedAt: Long = 0L

    /** Captures the cleanup epoch before a channel write suspends or waits on the platform lock. */
    fun captureEpoch(): Long = epoch.get()

    /** Records an account-wide channel cleanup while [UserEventNotificationGroup.mutationLock] is held. */
    fun recordAccountCleanup(
        accountRef: String?,
        includeUnscopedLegacy: Boolean,
        shortcutIds: Collection<String> = emptySet(),
    ) {
        val clearedAt = epoch.incrementAndGet()
        if (includeUnscopedLegacy) {
            allClearedAt = clearedAt
        }
        channelOwnerAccountHash(accountRef)?.let { key -> accountClearedAt[key] = clearedAt }
        shortcutIds.forEach { shortcutId ->
            shortcutClearedAt[shortcutId] = clearedAt
            shortcutClearedAt[canonicalConversationShortcutId(shortcutId)] = clearedAt
        }
    }

    /** Records a conversation-scoped channel cleanup while [UserEventNotificationGroup.mutationLock] is held. */
    fun recordConversationCleanup(shortcutIds: Collection<String>) {
        val clearedAt = epoch.incrementAndGet()
        shortcutIds.forEach { shortcutId ->
            shortcutClearedAt[shortcutId] = clearedAt
            shortcutClearedAt[canonicalConversationShortcutId(shortcutId)] = clearedAt
        }
    }

    /**
     * Returns true when no destructive cleanup for [accountRef] or [conversationShortcutId] ran
     * after [capturedEpoch]. Called inside [UserEventNotificationGroup.mutationLock].
     */
    fun isCurrent(
        capturedEpoch: Long,
        accountRef: String? = null,
        conversationShortcutId: String? = null,
    ): Boolean {
        val accountCleared = channelOwnerAccountHash(accountRef)?.let { accountClearedAt[it] } ?: 0L
        val shortcutCleared =
            conversationShortcutId?.let { shortcutId ->
                maxOf(
                    shortcutClearedAt[shortcutId] ?: 0L,
                    shortcutClearedAt[canonicalConversationShortcutId(shortcutId)] ?: 0L,
                )
            } ?: 0L
        val latestCleanup = maxOf(allClearedAt, accountCleared, shortcutCleared)
        return capturedEpoch >= latestCleanup
    }
}

/**
 * Returns the canonical SHA-256 owner key for [accountRef], rejecting blank or untrimmed values so
 * profile-channel prefixes and conversation-channel owner records enforce the same rule.
 */
internal fun channelOwnerAccountHash(accountRef: String?): String? {
    val canonical = accountRef?.takeIf { it.isNotBlank() && it == it.trim() }
    return canonical?.let(::sha256Hex)
}

/** Strips pinned and reaction suffixes to yield the canonical conversation shortcut id. */
internal fun canonicalConversationShortcutId(shortcutId: String): String {
    val unpinned = shortcutId.substringBefore(PIN_SUFFIX_MARKER)
    return unpinned.removeSuffix(REACTION_SUFFIX_MARKER)
}

/**
 * Expands a dynamic, reaction, or pinned shortcut id to every conversation shortcut id variant
 * (`<canonical>` and `<canonical>-reaction`, plus the input itself) a `:conv:` channel can carry.
 */
internal fun channelShortcutIdsFromShortcutId(shortcutId: String): List<String> {
    if (!isConversationShortcutId(shortcutId)) return emptyList()
    val canonical = canonicalConversationShortcutId(shortcutId)
    return listOf(shortcutId, canonical, "$canonical$REACTION_SUFFIX_MARKER").distinct()
}

/**
 * Expands known [groupIds] for [accountRef] into their canonical and `-reaction` conversation
 * shortcut ids, covering both exact and lowercased group ids for upgrade-era channels.
 */
internal fun groupChannelShortcutIds(
    accountRef: String,
    groupIds: Collection<String>,
): Set<String> =
    buildSet {
        groupIds.forEach { rawGroupId ->
            val trimmed = rawGroupId.trim()
            if (trimmed.isNotEmpty()) {
                val variants = listOf(trimmed, trimmed.lowercase(Locale.ROOT)).distinct()
                variants.forEach { groupId ->
                    conversationShortcutId(accountRef, groupId)?.let(::add)
                    reactionConversationShortcutId(accountRef, groupId)?.let(::add)
                }
            }
        }
    }
