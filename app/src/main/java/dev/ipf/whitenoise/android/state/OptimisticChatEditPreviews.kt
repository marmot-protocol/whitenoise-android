package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi

/** Transient user edit presentation; authoritative rows retain all identity, unread and ordering fields. */
internal class OptimisticChatEditPreviews {
    private data class Entry(
        val actionId: String,
        val original: ChatListMessagePreviewFfi,
        val text: String,
        val tokens: MarkdownDocumentFfi,
        val status: MessageStatus,
    )

    private val entries = mutableMapOf<String, Entry>()

    /** Only the currently selected, undeleted message can acquire an edit overlay. */
    fun begin(
        row: ChatListRowFfi,
        target: String,
        actionId: String,
        text: String,
        tokens: MarkdownDocumentFfi,
    ) {
        val preview = row.lastMessage?.takeIf { it.messageIdHex == target && !it.deleted } ?: return
        val prior = entries[row.groupIdHex]?.takeIf { it.original.messageIdHex == target }
        entries[row.groupIdHex] = Entry(actionId, prior?.original ?: preview, text, tokens, MessageStatus.Pending)
    }

    /** Completion is conditional on the exact action, including retries with identical text. */
    fun finish(
        group: String,
        actionId: String,
        status: MessageStatus,
    ) {
        val entry = entries[group]?.takeIf { it.actionId == actionId } ?: return
        entries[group] = entry.copy(status = status)
    }

    /** Discard/cancellation removes only the matching attempt, leaving a newer edit intact. */
    fun discard(
        group: String,
        actionId: String,
    ) {
        if (entries[group]?.actionId == actionId) entries.remove(group)
    }

    /** Account teardown drops transient presentation; native snapshots own subsequent restoration. */
    fun clear() = entries.clear()

    /** The newest native content, deletion or selected-message change settles a completed overlay. */
    fun project(row: ChatListRowFfi): ChatListRowFfi {
        val entry = entries[row.groupIdHex] ?: return row
        val native = row.lastMessage
        val selectionChanged = native == null || native.deleted || native.messageIdHex != entry.original.messageIdHex
        val contentSettled =
            entry.status == MessageStatus.Sent &&
                native?.let {
                    it.plaintext == entry.text || it.plaintext != entry.original.plaintext
                } == true
        return if (selectionChanged || contentSettled) {
            entries.remove(row.groupIdHex)
            row
        } else {
            projectPending(row, entry, requireNotNull(native))
        }
    }

    /** A transient status may replace only an already-delivered original message's status. */
    private fun projectPending(
        row: ChatListRowFfi,
        entry: Entry,
        native: ChatListMessagePreviewFfi,
    ): ChatListRowFfi {
        val failed = entry.status == MessageStatus.Failed
        val delivery =
            if (native.deliveryState == ChatListMessageDeliveryStateFfi.DELIVERED) {
                when (entry.status) {
                    MessageStatus.Pending -> ChatListMessageDeliveryStateFfi.PENDING
                    MessageStatus.Failed -> ChatListMessageDeliveryStateFfi.FAILED
                    else -> native.deliveryState
                }
            } else {
                native.deliveryState
            }
        return row.copy(
            lastMessage =
                native.copy(
                    plaintext = if (failed) entry.original.plaintext else entry.text,
                    contentTokens = if (failed) entry.original.contentTokens else entry.tokens,
                    deliveryState = delivery,
                ),
        )
    }
}

/** Captured account binding and action identity for a transient edit preview. */
class ChatEditPreview internal constructor(
    private val owner: ChatsController,
    private val epoch: Long,
    private val group: String,
    private val action: String,
) {
    /** Propagate native publication state without allowing a replaced account or edit to change. */
    internal fun finish(status: MessageStatus?) = owner.finishChatEditPreview(epoch, group, action, status)
}
