package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineMediaCaption
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Visible body selection for a timeline row — mirrors [MessageBubble] `displayedBody`. */
internal fun timelineMessageDisplayedBody(
    item: TimelineMessage,
    record: AppMessageRecordFfi,
    deleted: Boolean,
    persistedFailure: Boolean,
    editState: EditState?,
    deletedBodyText: String,
    invalidatedBodyText: String,
    messageTextCopy: MessageTextCopy,
): String =
    when {
        deleted -> deletedBodyText
        persistedFailure -> invalidatedBodyText
        editState != null && MessageProjector.isChatKind(record.kind) -> editState.latestText
        item.projected != null ->
            TimelineProjector.displayBody(
                item.projected,
                messageTextCopy.copy(
                    deleted = deletedBodyText,
                    invalidated = invalidatedBodyText,
                ),
                mediaCaptionHandoff =
                    TimelineMediaCaption.handoffPlaintext(item.projected, record),
            )
        else -> MessageProjector.displayBody(record, messageTextCopy)
    }

/** Caption / supplement text rendered under or beside confirmed media in [MessageBubble]. */
internal fun timelineMessageBubbleSupplementBody(
    deleted: Boolean,
    persistedFailure: Boolean,
    displayedBody: String,
    hideForStructuredShare: Boolean,
    mediaPendingName: String?,
    anyConfirmedMedia: Boolean,
    editState: EditState?,
    projected: TimelineMessageRecordFfi?,
    actionRecord: AppMessageRecordFfi,
): String? =
    when {
        deleted || persistedFailure -> displayedBody
        hideForStructuredShare -> null
        mediaPendingName != null && !anyConfirmedMedia -> null
        anyConfirmedMedia ->
            (
                editState?.latestText
                    ?: TimelineMediaCaption.effectivePlaintext(projected, actionRecord)
            ).takeIf { it.isNotBlank() }
        else -> displayedBody
    }
