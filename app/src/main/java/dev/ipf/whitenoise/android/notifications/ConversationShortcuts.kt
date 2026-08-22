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
)

internal fun directShareConversationShortcutCleanupPlan(dynamicShortcuts: List<ShortcutInfoCompat>): ConversationShortcutCleanupPlan =
    ConversationShortcutCleanupPlan(
        dynamicIds = conversationShortcutIds(dynamicShortcuts),
        longLivedIds = emptyList(),
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

internal fun conversationShortcutIdsForAccount(
    shortcuts: List<ShortcutInfoCompat>,
    accountRef: String,
): List<String> {
    val accountScope = conversationShortcutAccountScope(accountRef) ?: return emptyList()
    return shortcuts
        .filter { shortcut ->
            isConversationShortcutId(shortcut.id) &&
                shortcut.extras?.getString(CONVERSATION_SHORTCUT_ACCOUNT_SCOPE_EXTRA) == accountScope
        }.map { it.id }
        .distinct()
}

internal fun accountConversationShortcutCleanupPlan(
    shortcuts: List<ShortcutInfoCompat>,
    accountRef: String,
): ConversationShortcutCleanupPlan {
    val accountIds = conversationShortcutIdsForAccount(shortcuts, accountRef)
    return ConversationShortcutCleanupPlan(
        dynamicIds = accountIds,
        longLivedIds = accountIds,
    )
}

/** Destructively remove only the shortcuts owned by a signed-out or wiped account. */
internal fun clearConversationShortcutsForAccount(
    context: Context,
    accountRef: String,
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
        )
    if (plan.dynamicIds.isNotEmpty()) {
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, plan.dynamicIds) }
    }
    if (plan.longLivedIds.isNotEmpty()) {
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, plan.longLivedIds) }
    }
}
