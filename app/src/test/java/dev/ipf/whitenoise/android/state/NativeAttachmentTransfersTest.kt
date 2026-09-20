package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAttachmentTransfersTest {
    @Test
    fun `native demand is reserved for explicit requests with exact identity`() {
        val target = NativeAttachmentTarget("11".repeat(32), "22".repeat(32), 3)

        assertTrue(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Interactive, target))
        assertFalse(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Automatic, target))
        assertFalse(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Interactive, null))
    }

    @Test
    fun `native transfer states preserve terminal and active meaning`() {
        assertEquals(
            AttachmentTransferState.Available,
            status(AttachmentTransferStateFfi.READY).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Failed,
            status(AttachmentTransferStateFfi.FAILED).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Cancelled,
            status(AttachmentTransferStateFfi.CANCELLED).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Downloading,
            status(AttachmentTransferStateFfi.VERIFYING_PLAINTEXT).toPresentationState(),
        )
        assertEquals(
            AttachmentTransferState.Remote,
            status(AttachmentTransferStateFfi.POLICY_BLOCKED).toPresentationState(),
        )
    }

    private fun status(state: AttachmentTransferStateFfi) =
        AttachmentTransferStatusFfi(
            reference = null,
            state = state,
            attempt = 0u,
            received = 0u,
            total = null,
            retryAt = null,
        )
}
