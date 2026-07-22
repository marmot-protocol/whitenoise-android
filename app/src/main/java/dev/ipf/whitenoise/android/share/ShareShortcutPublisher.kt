package dev.ipf.whitenoise.android.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHARE_TARGET_CATEGORY
import dev.ipf.whitenoise.android.notifications.CONVERSATION_SHORTCUT_PREFIX
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.notificationConversationIcon
import dev.ipf.whitenoise.android.notifications.preferredConversationShortcutTitle
import dev.ipf.whitenoise.android.state.ChatListItem
import kotlin.math.min

internal const val MAX_SHARE_SHORTCUTS = 8
private const val SHARE_SHORTCUT_SHORT_LABEL_MAX = 24

data class ShareShortcutTarget(
    val accountRef: String,
    val groupIdHex: String,
    val title: String,
)

internal fun selectShareShortcutTargets(
    accountRef: String,
    chats: List<ChatListItem>,
    limit: Int = MAX_SHARE_SHORTCUTS,
    displayTitle: (ChatListItem) -> String,
): List<ShareShortcutTarget> =
    chats
        .filterNot { it.group.pendingConfirmation }
        .take(limit)
        .mapNotNull { item ->
            val groupId = item.group.groupIdHex.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ShareShortcutTarget(
                accountRef = accountRef,
                groupIdHex = groupId,
                title = displayTitle(item),
            )
        }

internal fun buildShareShortcutIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

internal fun buildShareShortcut(
    context: Context,
    target: ShareShortcutTarget,
    existingTitle: String? = null,
): ShortcutInfoCompat? {
    val shortcutId = conversationShortcutId(target.accountRef, target.groupIdHex) ?: return null
    val title = preferredConversationShortcutTitle(target.title, existingTitle)
    return ShortcutInfoCompat
        .Builder(context, shortcutId)
        .setShortLabel(title.take(SHARE_SHORTCUT_SHORT_LABEL_MAX).ifBlank { context.getString(R.string.app_name) })
        .setLongLabel(title)
        .setIcon(
            notificationConversationIcon(
                title = title,
                seed = shortcutId,
                avatarBitmap = null,
            ),
        ).setIntent(buildShareShortcutIntent(context))
        .setLongLived(true)
        .setCategories(setOf(CONVERSATION_SHARE_TARGET_CATEGORY))
        .build()
}

class ShareShortcutPublisher(
    private val context: Context,
    private val maxShortcutCount: () -> Int = {
        ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(0)
    },
    private val shortcutPublisher: (ShortcutInfoCompat) -> Unit = { shortcut ->
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    },
    private val removeShortcuts: (List<String>) -> Unit = { ids ->
        ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
    },
    private val existingShortcutIds: () -> Set<String> = {
        ShortcutManagerCompat
            .getDynamicShortcuts(context)
            .map { it.id }
            .filter { it.startsWith(CONVERSATION_SHORTCUT_PREFIX) }
            .toSet()
    },
) {
    fun publish(
        accountRef: String,
        chats: List<ChatListItem>,
        displayTitle: (ChatListItem) -> String,
    ) {
        if (accountRef.isBlank()) return
        val maxShortcuts = maxShortcutCount().coerceAtLeast(0)
        if (maxShortcuts <= 0) return
        val limit = min(MAX_SHARE_SHORTCUTS, maxShortcuts)
        val targets = selectShareShortcutTargets(accountRef, chats, limit, displayTitle)
        val desiredIds =
            targets
                .mapNotNull { conversationShortcutId(accountRef, it.groupIdHex) }
                .toSet()
        val existing = existingShortcutIds()
        val stale = existing - desiredIds
        if (stale.isNotEmpty()) {
            removeShortcuts(stale.toList())
        }
        targets.forEach { target ->
            val shortcutId = conversationShortcutId(accountRef, target.groupIdHex) ?: return@forEach
            val existingTitle =
                ShortcutManagerCompat
                    .getDynamicShortcuts(context)
                    .firstOrNull { it.id == shortcutId }
                    ?.longLabel
                    ?.toString()
            val shortcut = buildShareShortcut(context, target, existingTitle) ?: return@forEach
            shortcutPublisher(shortcut)
        }
    }
}
