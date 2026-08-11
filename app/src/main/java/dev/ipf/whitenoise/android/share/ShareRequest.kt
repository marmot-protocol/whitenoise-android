package dev.ipf.whitenoise.android.share

import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import java.util.UUID

/**
 * One inbound share awaiting UI consumption. [shortcutId] is resolved against
 * the active account's chat list in [resolveShareDirectGroupId].
 */
data class ShareRequest(
    val payload: SharePayload,
    val shortcutId: String?,
    val requestId: String = "",
)

fun parseShareRequest(intent: Intent?): ShareRequest? {
    val payload = parseShareIntent(intent) ?: return null
    return ShareRequest(
        payload = payload,
        shortcutId = intent?.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)?.takeIf { it.isNotBlank() },
        requestId = UUID.randomUUID().toString(),
    )
}

/**
 * Resolve a Direct Share target to a group id from the active account's chats only.
 * Returns null when the shortcut does not map to a known active group.
 */
fun resolveShareDirectGroupId(
    request: ShareRequest,
    accountRef: String,
    activeGroupIds: Set<String>,
): String? {
    if (accountRef.isBlank() || activeGroupIds.isEmpty()) return null
    return request.shortcutId?.let { shortcutId ->
        activeGroupIds.firstOrNull { groupId ->
            conversationShortcutId(accountRef, groupId) == shortcutId
        }
    }
}
