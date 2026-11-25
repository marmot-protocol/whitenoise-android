package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.mutableStateOf
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.GROUP_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.MESSAGE_ID
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.TARGET_REF
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.appState
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.controller
import dev.ipf.whitenoise.android.state.ConversationActionFixtures.native
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.state.MessageReportRegistry
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ClipboardCopier
import dev.ipf.whitenoise.android.ui.conversation.PollMessageActionOwner
import dev.ipf.whitenoise.android.ui.conversation.reactions.LocalReactionMotion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The message menu runs Edit and Reply after its exit animation. If the pending send it was opened for confirms in
 * that window, the call reaches the bubble's replaced actions holder, which still carries the pending record. Edit
 * and Reply must then act on the confirmed message, so the composer opens with its text; while the send is still
 * pending, or when nothing confirmed it, they keep the pending record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class MessageBubbleActionsConfirmedTargetTest {
    @Test
    fun queuedEditAndReplyTargetTheConfirmedMessage() =
        runTest {
            val state = appState(native { name, _ -> throw UnsupportedOperationException(name) })
            val controller = controller(state)
            val confirmed = localSend(record(MESSAGE_ID), MessageStatus.Sent)
            controller.timelineItemsById[confirmed.id] = confirmed
            controller.timeline = listOf(confirmed)
            val actions = pendingHolder(state, controller)

            actions.beginEdit()
            assertEquals(MESSAGE_ID, controller.editingMessageId)

            actions.beginReply()
            assertEquals(MESSAGE_ID, controller.replyingTo?.messageIdHex)
        }

    @Test
    fun editAndReplyKeepTheRecordWhileTheSendIsPending() =
        runTest {
            val state = appState(native { name, _ -> throw UnsupportedOperationException(name) })
            val controller = controller(state)
            val pending = localSend(record(PENDING_ID), MessageStatus.Pending)
            controller.optimisticMessages[pending.id] = pending
            controller.timeline = listOf(pending)
            val actions = pendingHolder(state, controller)

            actions.beginEdit()
            assertEquals(PENDING_ID, controller.editingMessageId)

            actions.beginReply()
            assertEquals(PENDING_ID, controller.replyingTo?.messageIdHex)
        }

    @Test
    fun editKeepsTheRecordWhenNothingConfirmedTheSend() =
        runTest {
            val state = appState(native { name, _ -> throw UnsupportedOperationException(name) })
            val controller = controller(state)
            val actions = pendingHolder(state, controller)

            actions.beginEdit()

            assertEquals(PENDING_ID, controller.editingMessageId)
        }

    /** The actions holder a pending bubble remembered, holding the inputs from its own lifetime. */
    private fun pendingHolder(
        state: WhiteNoiseAppState,
        controller: ConversationController,
    ): MessageBubbleActions {
        val item = localSend(record(PENDING_ID), MessageStatus.Pending)
        return MessageBubbleActions(
            appState = state,
            controller = controller,
            surfaces = MessageBubbleSurfaceState(MessageReportRegistry(), item.presentationId),
            pollOwner = PollMessageActionOwner(TARGET_REF, GROUP_ID, PENDING_ID),
            latest =
                mutableStateOf(
                    MessageBubbleActions.Inputs(
                        item = item,
                        isPollRecord = false,
                        deleted = false,
                        actionsReadOnly = false,
                        deleteCapability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
                        forwardPayload = null,
                        visibleTranslation = null,
                        displayedBody = "Original",
                        messageTextCopy = TEXT_COPY,
                        localReactionMotion = LocalReactionMotion(),
                        clipboard = ClipboardCopier(RuntimeEnvironment.getApplication()) {},
                        onActionMenuOpenChange = {},
                        onForwardMenuHandoff = {},
                    ),
                ),
        )
    }

    private companion object {
        const val PENDING_ID = "6f1d2a3b-0c4d-4e5f-8a9b-0c1d2e3f4a5b"
        val EMPTY_MARKDOWN =
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        val TEXT_COPY =
            MessageTextCopy(
                reactedFormat = "R:%1\$s",
                reactionFallback = "target",
                deleted = "deleted",
                invalidated = "invalidated",
                partialVisibility = "partial",
                nonCanonicalHistory = "noncanonical",
                deliveryNotConfirmed = "unconfirmed",
                mediaAttachment = "media",
                message = "message",
            )

        /** The user's own text with [id]: the client token while it sends, then its event id. */
        fun record(id: String) =
            AppMessageRecordFfi(
                messageIdHex = id,
                invalidated = false,
                direction = "sent",
                groupIdHex = GROUP_ID,
                sender = "f6".repeat(32),
                plaintext = "Original",
                contentTokens = EMPTY_MARKDOWN,
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = 1uL,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
                sticker = null,
            )

        /** A row of the user's own send started under [PENDING_ID], now showing [record] in [status]. */
        fun localSend(
            record: AppMessageRecordFfi,
            status: MessageStatus,
        ) = TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = record,
            status = status,
            presentationId = "msg:$PENDING_ID",
        )
    }
}
