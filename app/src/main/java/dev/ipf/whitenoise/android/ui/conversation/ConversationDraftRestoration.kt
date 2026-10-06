package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import kotlinx.coroutines.delay

private const val DRAFT_RESTORATION_RETRY_DELAY_MILLIS = 250L

/** One bounded retry keeps a transient initial read from stranding the document shelf. */
internal suspend fun readDraftForRestoration(
    drafts: MessageDraftRepository,
    accountRef: String,
    groupIdHex: String,
): Result<MessageDraftFfi?> {
    val initial = drafts.draft(accountRef, groupIdHex)
    if (initial.isSuccess) return initial
    delay(DRAFT_RESTORATION_RETRY_DELAY_MILLIS)
    return drafts.draft(accountRef, groupIdHex)
}
