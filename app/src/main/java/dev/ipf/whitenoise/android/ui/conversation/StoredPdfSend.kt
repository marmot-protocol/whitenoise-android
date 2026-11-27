package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftFileCopy
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget
import dev.ipf.whitenoise.android.ui.conversation.media.scrubPdfMetadata

/** Stored PDFs need the same privacy policy before they can take the direct native draft upload path. */
@Suppress("LongParameterList", "ReturnCount") // In-memory attachments and unbound conversations need no copy.
internal suspend fun WhiteNoiseAppState.prepareStoredPdfForSend(
    controller: ConversationController,
    attachment: PendingAttachment,
    allowFileBacked: Boolean,
    keepMetadata: Boolean,
    budget: () -> FileBackedPickBudget?,
): DocumentReadOutcome? {
    val stored = attachment.draftFile ?: return null
    val account = controller.boundAccountRef ?: return null
    return marmotIo {
        val draft = DraftFileCopy(this, account, controller.group.groupIdHex, stored)
        if (!draft.isPdf()) return@marmotIo null
        if (!allowFileBacked) {
            return@marmotIo DocumentReadOutcome(emptyList(), setOf(DocumentReadFailure.TOO_LARGE), false, 0L)
        }
        val pick =
            budget()?.stageStored(stored.byteCount, draft::into) { source ->
                if (!keepMetadata) source.scrubPdfMetadata()
                true
            }
        val staged =
            pick?.source?.let { attachment.copy(sourceFile = it, draftFile = null, nativeDraftFileBody = stored) }
        DocumentReadOutcome(
            listOfNotNull(staged),
            if (staged == null) setOf(pick?.failure.toDocumentReadFailure()) else emptySet(),
            false,
            0L,
        )
    }
}
