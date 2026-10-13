package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderResult

/** Preserve the previous attachment while explaining a typed local processing refusal. */
internal fun photoEditorSaveFailureMessage(
    result: PhotoEditorCommitResult,
    messages: PhotoEditorMessages,
): String =
    if (result is PhotoEditorCommitResult.RenderFailed && result.result == PhotoEditorRenderResult.MemoryLimit) {
        messages.budgetExceeded
    } else {
        messages.saveFailed
    }
