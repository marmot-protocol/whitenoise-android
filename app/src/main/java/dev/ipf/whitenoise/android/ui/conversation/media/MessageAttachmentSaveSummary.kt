package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.ui.conversation.messages.MessageAttachmentSaveOutcome

internal data class MessageAttachmentSaveSummary(
    val savedCount: Int,
    val totalCount: Int,
    val firstFailure: Throwable? = null,
) {
    val outcome: MessageAttachmentSaveOutcome
        get() = MessageAttachmentSaveOutcome.from(savedCount, totalCount)

    companion object {
        val Empty = MessageAttachmentSaveSummary(savedCount = 0, totalCount = 0)
    }
}

internal fun aggregateMessageAttachmentSaveSummaries(summaries: Iterable<MessageAttachmentSaveSummary>) =
    summaries.fold(MessageAttachmentSaveSummary.Empty) { aggregate, summary ->
        MessageAttachmentSaveSummary(
            savedCount = aggregate.savedCount + summary.savedCount,
            totalCount = aggregate.totalCount + summary.totalCount,
            firstFailure = aggregate.firstFailure ?: summary.firstFailure,
        )
    }
