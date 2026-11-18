package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.replyAllowsTaggedSend

/**
 * The attachment menu's Create poll action, or null where no poll can be sent: in a direct chat, or while the
 * reply points at a message that has no event id yet, so the poll could not reference it.
 */
internal fun ConversationController.createPollAction(open: () -> Unit): (() -> Unit)? =
    open.takeIf {
        !isDirectConversation && replyAllowsTaggedSend()
    }

/**
 * The Stickers action, or null during an edit, which changes an existing message, and while the reply points
 * at a message that has no event id yet.
 */
internal fun ConversationController.stickerSheetAction(open: () -> Unit): (() -> Unit)? =
    open.takeIf {
        editingMessageId == null && replyAllowsTaggedSend()
    }
