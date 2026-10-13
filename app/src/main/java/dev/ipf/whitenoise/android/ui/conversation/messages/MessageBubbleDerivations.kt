package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardEligibility
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CoroutineScope

// These calculations used to sit inside MessageBubble's remember blocks, which are inlined into the bubble's own
// bytecode. Keeping them here holds MessageBubble below ART's per-method compile limit (#3150).

/**
 * The body text a bubble shows: its tombstone or invalidation notice, the latest edit of a kind-9 chat, else the
 * projected or raw message body.
 *
 * The projection path receives the deleted and invalidated copy, while the raw fallback keeps the caller's copy.
 */
@Suppress("LongParameterList") // Each input is supplied by the calling bubble's remember block.
internal fun messageBubbleDisplayedBody(
    item: TimelineMessage,
    deleted: Boolean,
    persistedFailure: Boolean,
    editState: EditState?,
    textCopy: MessageTextCopy,
    deletedBodyText: String,
    invalidatedBodyText: String,
): String =
    when {
        // Check `deleted` first so the optimistic tombstone (from
        // controller.deletedMessageIds) renders immediately on tap.
        deleted -> deletedBodyText
        persistedFailure -> invalidatedBodyText
        // Edit overlay wins over both projected and raw plaintext.
        // We don't go through MessageProjector here — the edit
        // payload is plain text by spec; MessageBubble re-parses
        // markdown when record.contentTokens is populated, but
        // for kind-9 edits the body is the latest version verbatim.
        editState != null && item.record.kind == CHAT_MESSAGE_KIND -> editState.latestText
        item.projected != null ->
            TimelineProjector.displayBody(
                item.projected,
                textCopy.copy(
                    deleted = deletedBodyText,
                    invalidated = invalidatedBodyText,
                ),
            )
        else -> MessageProjector.displayBody(item.record, textCopy)
    }

/**
 * Whether a bubble can be forwarded and, when it cannot, why. A deleted, invalidated or failed committed message is
 * unsupported, a failed send that still carries pending media is a failed attachment, and an unconfirmed send is a
 * pending attachment when it carries pending media and unsupported otherwise. A sent or received message defers to
 * the projector with its cached attachments.
 */
@Suppress("LongParameterList") // Each input is supplied by the calling bubble's remember block.
internal fun messageBubbleForwardEligibility(
    item: TimelineMessage,
    editState: EditState?,
    deleted: Boolean,
    invalidated: Boolean,
    persistedFailure: Boolean,
    attachments: MessageAttachmentSet,
    controller: ConversationController,
    nowSeconds: ULong,
): ForwardEligibility {
    val record = item.record
    val failedCommittedMessage = persistedFailure && !MessageProjector.isPendingMedia(record)
    return when {
        deleted || invalidated || failedCommittedMessage -> ForwardEligibility.Blocked(ForwardBlockedReason.Unsupported)
        persistedFailure -> ForwardEligibility.Blocked(ForwardBlockedReason.FailedAttachment)
        item.status != MessageStatus.Received && item.status != MessageStatus.Sent ->
            ForwardEligibility.Blocked(
                if (MessageProjector.isPendingMedia(record)) {
                    ForwardBlockedReason.PendingAttachment
                } else {
                    ForwardBlockedReason.Unsupported
                },
            )
        else ->
            MessageProjector.forwardEligibility(
                message = record,
                mediaReferences = attachments.references,
                editedText = editState?.latestText?.takeIf { record.kind == CHAT_MESSAGE_KIND },
                cachedAttachmentIndices =
                    attachments.accepted
                        .map { it.index }
                        .filterTo(mutableSetOf()) { attachmentIndex ->
                            controller.hasCachedAttachment(record.messageIdHex, attachmentIndex)
                        },
                nowSeconds = nowSeconds,
            )
    }
}

/**
 * Defers a bubble's link activation while this conversation owns read-aloud, so a double tap can seek instead of
 * opening the link. Ownership is read when a tap arrives, not when the coordinator is created.
 */
internal fun messageBubbleTtsLinkTapCoordinator(
    scope: CoroutineScope,
    appState: WhiteNoiseAppState,
    controller: ConversationController,
): TtsLinkTapCoordinator =
    TtsLinkTapCoordinator(
        scope = scope,
        isReadAloudActive = {
            val state = appState.ttsController.state.value
            (state is TtsState.Speaking || state is TtsState.Paused) &&
                appState.ownsTtsAutoReadSession(controller.group.groupIdHex)
        },
        doubleTapTimeoutMillis =
            android.view.ViewConfiguration
                .getDoubleTapTimeout()
                .toLong(),
    )

private const val CHAT_MESSAGE_KIND = 9uL
