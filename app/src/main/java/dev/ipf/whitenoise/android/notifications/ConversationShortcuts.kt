package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat

internal fun isConversationShortcutId(shortcutId: String): Boolean = shortcutId.startsWith(CONVERSATION_SHORTCUT_PREFIX)

/**
 * Rich notification shortcuts include a locus id; the chat-list publisher only
 * builds generic shortcuts without one, so preserving locus metadata is enough
 * to avoid downgrading notification-published shortcuts.
 */
internal fun conversationShortcutIsRich(shortcut: ShortcutInfoCompat): Boolean = shortcut.locusId != null

internal fun conversationShortcutIds(shortcuts: List<ShortcutInfoCompat>): List<String> =
    shortcuts
        .map { it.id }
        .filter(::isConversationShortcutId)
        .distinct()

internal data class ConversationShortcutCleanupPlan(
    val dynamicIds: List<String>,
    val longLivedIds: List<String>,
    val disabledIds: List<String>,
)

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
 * signed-in account remains and therefore cannot own one of those entries.
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

/** Destructively remove only the shortcuts owned by a signed-out or wiped account. */
internal fun clearConversationShortcutsForAccount(
    context: Context,
    accountRef: String,
    includeUnscopedLegacy: Boolean,
) {
    val plan =
        accountConversationShortcutCleanupPlan(
            shortcuts =
                runCatching {
                    ShortcutManagerCompat.getShortcuts(
                        context,
                        ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                            ShortcutManagerCompat.FLAG_MATCH_CACHED or
                            ShortcutManagerCompat.FLAG_MATCH_PINNED,
                    )
                }.getOrElse {
                    ShortcutManagerCompat.getDynamicShortcuts(context)
                },
            accountRef = accountRef,
            includeUnscopedLegacy = includeUnscopedLegacy,
        )
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
