package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.MessageDraftMergeCompletion
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult

/** Both Android intake paths reject a merge completion after a newer edit or accepted send. */
internal fun CoalescingMessageDraftWriter.hydrateMergedDraft(
    store: DraftStore,
    accountRef: String,
    groupIdHex: String,
    completion: MessageDraftMergeCompletion,
    onHydrated: () -> Unit,
) {
    if (completion.result !is MessageDraftMutationResult.Success) return
    val generation = completion.generation ?: return
    runHydrationIfCurrent(accountRef, groupIdHex, generation) {
        completion.contentForHydration?.let { content ->
            store.hydrate(
                accountRef,
                groupIdHex,
                content,
                completion.draftedAtMs ?: System.currentTimeMillis(),
                replaceExisting = true,
            )
            onHydrated()
        }
    }
}
