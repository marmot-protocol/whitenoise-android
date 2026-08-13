package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.privacySafeErrorPresentation
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageAttachmentSaveOutcome

internal data class MediaSavePresentation(
    @StringRes val titleRes: Int,
    val detail: AppText? = null,
)

internal fun MessageAttachmentSaveSummary.presentation(context: Context): MediaSavePresentation =
    when (outcome) {
        MessageAttachmentSaveOutcome.Complete ->
            MediaSavePresentation(titleRes = R.string.shared_media_saved)
        MessageAttachmentSaveOutcome.Partial ->
            MediaSavePresentation(
                titleRes = R.string.shared_media_saved,
                detail =
                    AppText.Plain(
                        context.getString(R.string.conversation_search_match_count, savedCount, totalCount),
                    ),
            )
        MessageAttachmentSaveOutcome.Failed ->
            MediaSavePresentation(titleRes = R.string.shared_media_save_failed)
    }

internal fun WhiteNoiseAppState.presentAttachmentSaveOutcome(
    context: Context,
    summary: MessageAttachmentSaveSummary,
    conversation: ConversationNoticeDestination? = null,
) {
    when (summary.outcome) {
        MessageAttachmentSaveOutcome.Complete,
        MessageAttachmentSaveOutcome.Partial,
        -> {
            val presentation = summary.presentation(context)
            if (conversation == null) {
                presentTransient(presentation.titleRes, presentation.detail)
            } else {
                presentConversationTransient(
                    accountRef = conversation.accountRef,
                    groupIdHex = conversation.groupIdHex,
                    titleRes = presentation.titleRes,
                    detail = presentation.detail,
                )
            }
        }
        MessageAttachmentSaveOutcome.Failed ->
            presentFailure(
                titleRes = R.string.shared_media_save_failed,
                operationCode = "MESSAGE_ATTACHMENT_SAVE",
                throwable = summary.firstFailure ?: IllegalStateException("Attachment save failed"),
            )
    }
}

internal fun WhiteNoiseAppState.presentAttachmentSaveOutcome(
    context: Context,
    savedCount: Int,
    totalCount: Int,
    conversation: ConversationNoticeDestination? = null,
) {
    presentAttachmentSaveOutcome(
        context = context,
        summary = MessageAttachmentSaveSummary(savedCount = savedCount, totalCount = totalCount),
        conversation = conversation,
    )
}

/** Presents a single MediaStore save without exposing exception text or attachment names. */
internal fun WhiteNoiseAppState.presentMediaSaveOutcome(
    outcome: Result<Unit>,
    @StringRes successTitleRes: Int,
    @StringRes failureTitleRes: Int,
    operationCode: String,
    conversation: ConversationNoticeDestination? = null,
) {
    outcome.fold(
        onSuccess = {
            if (conversation == null) {
                presentTransient(successTitleRes)
            } else {
                presentConversationTransient(
                    accountRef = conversation.accountRef,
                    groupIdHex = conversation.groupIdHex,
                    titleRes = successTitleRes,
                )
            }
        },
        onFailure = { failure ->
            presentFailure(
                titleRes = failureTitleRes,
                operationCode = operationCode,
                throwable = failure,
            )
        },
    )
}

/** Local viewer equivalent for screens that own their SnackbarHostState. */
internal fun mediaSaveSnackbarVisuals(
    context: Context,
    outcome: Result<Unit>,
    @StringRes successTitleRes: Int,
    @StringRes failureTitleRes: Int,
    operationCode: String,
): ToastSnackbarVisuals =
    outcome.fold(
        onSuccess = { ToastSnackbarVisuals(context.getString(successTitleRes)) },
        onFailure = { failure ->
            val presentation =
                privacySafeErrorPresentation(
                    operationCode = operationCode,
                    throwable = failure,
                )
            ToastSnackbarVisuals(
                message = context.getString(failureTitleRes),
                copyable = true,
                copyText = presentation.report,
            )
        },
    )
