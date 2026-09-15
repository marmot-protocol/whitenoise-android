package dev.ipf.whitenoise.android.ui.chats

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ipf.marmotkit.ChatListAttachmentKindFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatListItemEvicted
import dev.ipf.whitenoise.android.state.ChatListItem

/** Native membership facts keep disband, removal, voluntary leave and pending leave distinct. */
@StringRes
internal fun chatRowMembershipStatus(
    item: ChatListItem,
    accountIdHex: String?,
): Int? {
    val groupEnding = item.group.disbanded || item.group.disbanding || item.group.leaveRequestPending
    if (accountIdHex.isNullOrBlank() || (!item.removedFromGroup(accountIdHex) && !groupEnding)) return null
    return chatRowTerminalGroupStatus(item) ?: when {
        chatListItemEvicted(item) -> R.string.chat_row_removed_description
        item.group.selfMembership == SelfMembershipFfi.LEFT ||
            item.projection?.selfMembership == SelfMembershipFfi.LEFT -> R.string.group_system_you_member_left
        item.group.leaveRequestPending || item.projection?.leaveRequestPending == true -> R.string.leaving_chat
        else -> R.string.you_are_no_longer_a_member
    }
}

/** Terminal group lifecycle labels retain their original precedence over membership labels. */
@StringRes
private fun chatRowTerminalGroupStatus(item: ChatListItem): Int? =
    when {
        item.group.disbanded || item.projection?.lifecycleState == GroupLifecycleStateFfi.DISBANDED ->
            R.string.conversation_disbanded_notice
        item.group.disbanding || item.projection?.disbanding == true -> R.string.conversation_disbanding_notice
        else -> null
    }

/** The typed native attachment kind determines its existing compact row icon. */
@DrawableRes
internal fun chatRowAttachmentIcon(kind: ChatListAttachmentKindFfi): Int =
    when (kind) {
        ChatListAttachmentKindFfi.PHOTO -> R.drawable.ic_image
        ChatListAttachmentKindFfi.VIDEO -> R.drawable.ic_play_arrow
        ChatListAttachmentKindFfi.AUDIO -> R.drawable.ic_mic
        ChatListAttachmentKindFfi.FILE, ChatListAttachmentKindFfi.MIXED -> R.drawable.ic_description
    }
