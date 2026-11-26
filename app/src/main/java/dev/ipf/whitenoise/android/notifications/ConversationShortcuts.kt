package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

/** Recognizes only app-owned conversation IDs so launcher cleanup leaves unrelated entries alone. */
internal fun isConversationShortcutId(shortcutId: String): Boolean = shortcutId.startsWith(CONVERSATION_SHORTCUT_PREFIX)

/**
 * Rich notification shortcuts include a locus id; the chat-list publisher only
 * builds generic shortcuts without one, so preserving locus metadata is enough
 * to avoid downgrading notification-published shortcuts.
 */
internal fun conversationShortcutIsRich(shortcut: ShortcutInfoCompat): Boolean = shortcut.locusId != null

/** Deduplicates owned conversation IDs before issuing platform removal calls. */
internal fun conversationShortcutIds(shortcuts: List<ShortcutInfoCompat>): List<String> =
    shortcuts
        .map { it.id }
        .filter(::isConversationShortcutId)
        .distinct()

/**
 * The conversation shortcuts to remove before [shortcutId] can be published under the per-activity cap, least recently
 * used first, never one of [protectedShortcutIds] (shortcuts backing a live card or held for a pending card write).
 * Returns null when the protected shortcuts leave too little room, so the caller skips the publication rather than
 * let the platform evict a protected shortcut.
 */
internal fun conversationShortcutPrunePlan(
    dynamicShortcutIds: Set<String>,
    manifestShortcutCount: Int,
    maxShortcuts: Int,
    shortcutId: String,
    protectedShortcutIds: Set<String>,
    lastUsed: Map<String, Long>,
): List<String>? {
    val removeCount =
        conversationShortcutPruneCount(dynamicShortcutIds, manifestShortcutCount, maxShortcuts, shortcutId)
    if (removeCount <= 0) return emptyList()
    val removable =
        dynamicShortcutIds.filterTo(mutableSetOf()) {
            it.startsWith(CONVERSATION_SHORTCUT_PREFIX) && it !in protectedShortcutIds
        }
    return conversationShortcutRemovalOrder(removable, lastUsed, shortcutId)
        .take(removeCount)
        .takeIf { it.size == removeCount }
}

internal data class ConversationShortcutCleanupPlan(
    val dynamicIds: List<String>,
    val longLivedIds: List<String>,
    val disabledIds: List<String>,
)

/** Hides dynamic share targets while preserving pinned or long-lived conversation ownership. */
internal fun directShareConversationShortcutCleanupPlan(dynamicShortcuts: List<ShortcutInfoCompat>) =
    ConversationShortcutCleanupPlan(
        dynamicIds = conversationShortcutIds(dynamicShortcuts),
        longLivedIds = emptyList(),
        disabledIds = emptyList(),
    )

/**
 * Hide every conversation from Direct Share without deleting its long-lived
 * Android conversation. Removing a long-lived shortcut also deletes its
 * conversation channel and active notifications, so account switching must
 * never use the destructive API.
 */
internal fun hideConversationShortcutsFromDirectShare(context: Context) {
    val plan =
        directShareConversationShortcutCleanupPlan(
            runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }.getOrDefault(emptyList()),
        )
    if (plan.dynamicIds.isEmpty()) return
    runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, plan.dynamicIds) }
}

/**
 * Select shortcuts that are provably owned by [accountRef]. Legacy shortcuts
 * lack an owner stamp, so [includeUnscopedLegacy] is safe only when no other
 * signing account remains and therefore cannot own one of those entries.
 * Read-only accounts are excluded: without a signer they cannot decrypt
 * messages, so they post no notifications and own no conversation shortcuts
 * or channels.
 */
internal fun conversationShortcutIdsForAccount(
    shortcuts: List<ShortcutInfoCompat>,
    accountRef: String,
    includeUnscopedLegacy: Boolean,
): List<String> {
    val accountScope = conversationShortcutAccountScope(accountRef) ?: return emptyList()
    return shortcuts
        .filter { shortcut ->
            if (!isConversationShortcutId(shortcut.id)) return@filter false
            val ownerScope = shortcut.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA)
            ownerScope == accountScope || (includeUnscopedLegacy && ownerScope.isNullOrBlank())
        }.map { it.id }
        .distinct()
}

/** Plans destructive removal only for the account-scoped IDs accepted by the ownership filter. */
internal fun accountConversationShortcutCleanupPlan(
    shortcuts: List<ShortcutInfoCompat>,
    accountRef: String,
    includeUnscopedLegacy: Boolean,
): ConversationShortcutCleanupPlan {
    val accountIds = conversationShortcutIdsForAccount(shortcuts, accountRef, includeUnscopedLegacy)
    return ConversationShortcutCleanupPlan(
        dynamicIds = accountIds,
        longLivedIds = accountIds,
        disabledIds = accountIds,
    )
}

/**
 * Removes platform shortcuts and owned notification channels after the caller has durably revoked
 * and fenced account credentials.
 */
internal fun clearRevokedConversationShortcutsForAccount(
    context: Context,
    accountRef: String,
    includeUnscopedLegacy: Boolean,
    additionalShortcutIds: Collection<String> = emptySet(),
    knownGroupIds: Collection<String> = emptySet(),
) {
    synchronized(UserEventNotificationGroup.mutationLock) {
        val shortcuts =
            runCatching {
                ShortcutManagerCompat.getShortcuts(
                    context,
                    ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                        ShortcutManagerCompat.FLAG_MATCH_CACHED or
                        ShortcutManagerCompat.FLAG_MATCH_PINNED,
                )
            }.getOrElse {
                runCatching { ShortcutManagerCompat.getDynamicShortcuts(context) }.getOrDefault(emptyList())
            }
        val plan =
            accountConversationShortcutCleanupPlan(
                shortcuts = shortcuts,
                accountRef = accountRef,
                includeUnscopedLegacy = includeUnscopedLegacy,
            )
        // Redact and delete channels before removing shortcuts: Android can soft-delete a linked
        // channel on shortcut removal, keeping its private name and hiding it from enumeration.
        val channelsCleared =
            runCatching {
                deleteAccountNotificationChannels(
                    context = context,
                    accountRef = accountRef,
                    shortcuts = shortcuts,
                    accountShortcutIds = plan.longLivedIds + additionalShortcutIds,
                    includeUnscopedLegacy = includeUnscopedLegacy,
                    knownGroupIds = knownGroupIds,
                )
            }.getOrDefault(false)
        // Pinned shortcuts can remain on the launcher after disablement, so redact their labels first.
        val affected = plan.disabledIds.toSet()
        val relabeled =
            shortcuts
                .filter { it.id in affected && (!channelsCleared || it.isPinned) }
                .map { genericNotificationShortcut(context, it) }
        val redacted =
            relabeled.isEmpty() ||
                runCatching { ShortcutManagerCompat.updateShortcuts(context, relabeled) }.getOrDefault(false)
        if (!channelsCleared || !redacted) return@synchronized
        if (plan.disabledIds.isNotEmpty()) {
            runCatching { ShortcutManagerCompat.disableShortcuts(context, plan.disabledIds, "") }
        }
        if (plan.dynamicIds.isNotEmpty()) {
            runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, plan.dynamicIds) }
        }
        if (plan.longLivedIds.isNotEmpty()) {
            runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, plan.longLivedIds) }
        }
    }
}
