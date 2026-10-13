package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.media.editor.PhotoEditorCommitResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderResult
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoEditorSaveFailureMessageTest {
    private val messages = PhotoEditorMessages("animation", "source", "unavailable", "save", "budget")

    @Test
    fun processingLimitIsDistinguishedFromOtherFailuresWithoutClaimingSuccess() {
        val budgetFailure = PhotoEditorCommitResult.RenderFailed(PhotoEditorRenderResult.MemoryLimit)
        val encodeFailure = PhotoEditorCommitResult.RenderFailed(PhotoEditorRenderResult.EncodeFailed)
        assertEquals("budget", photoEditorSaveFailureMessage(budgetFailure, messages))
        assertEquals("save", photoEditorSaveFailureMessage(encodeFailure, messages))
        assertEquals("save", photoEditorSaveFailureMessage(PhotoEditorCommitResult.StaleAttachment, messages))
    }
}
