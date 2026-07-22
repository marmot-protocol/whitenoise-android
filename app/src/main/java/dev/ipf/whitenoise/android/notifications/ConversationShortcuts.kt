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

/**
 * Remove every conversation shortcut from dynamic and long-lived/cached Direct
 * Share surfaces. Call on account switch and last-account wipe so another
 * account's chats cannot linger in the share target list.
 */
internal fun clearAllConversationShortcuts(context: Context) {
    val conversationIds =
        conversationShortcutIds(
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
        )
    if (conversationIds.isEmpty()) return
    runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, conversationIds) }
    runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, conversationIds) }
    runCatching { ShortcutManagerCompat.disableShortcuts(context, conversationIds, "") }
}
