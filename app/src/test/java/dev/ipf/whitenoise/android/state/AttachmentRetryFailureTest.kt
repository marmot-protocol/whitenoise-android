package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Reference projection delay must remain reloadable without granting terminal retry permission. */
class AttachmentRetryFailureTest {
    /** A cancel that loses to verified completion cannot reset a budget or reacquire cached bytes on Open. */
    @Test
    fun availableOpenNeverNeedsRetryEvenWhenAutomaticWorkIsSuppressed() {
        assertFalse(attachmentActionNeedsRetry(AttachmentTransferState.Available, automaticSuppressed = true))
        assertTrue(attachmentActionNeedsRetry(AttachmentTransferState.Cancelled, automaticSuppressed = true))
        assertTrue(attachmentActionNeedsRetry(AttachmentTransferState.Failed, automaticSuppressed = false))
    }

    /** A stale native phase cannot turn acknowledged host cancellation back into a failed retry icon. */
    @Test
    fun acknowledgedHostCancellationWinsOverStaleNativeFailure() {
        assertEquals(
            AttachmentTransferState.Cancelled,
            attachmentFilePresentationState(
                AttachmentTransferState.Cancelled,
                NativeAttachmentProgress(
                    dev.ipf.marmotkit.AttachmentTransferStateFfi.FAILED,
                    1u,
                    0u,
                    null,
                    null,
                    "failure",
                ),
                AttachmentCancellationState.None,
            ),
        )
    }

    /** The prior viewer/materialization reload path is retained for an unresolved native target. */
    @Test
    fun unresolvedReferenceReopensWithoutReportingAnAdmissionFailure() {
        val events = mutableListOf<String>()
        deliverAttachmentRetryFailure(AttachmentReferenceNotReadyException(), {
            events += "open"
            true
        }, { events += "error" })
        assertEquals(listOf("open"), events)
    }

    /** If navigation prevents reload, the action still reports an error instead of failing silently. */
    @Test
    fun refusedReloadReportsFailure() {
        val events = mutableListOf<String>()
        deliverAttachmentRetryFailure(AttachmentReferenceNotReadyException(), {
            events += "open"
            false
        }, { events += "error" })
        assertEquals(listOf("open", "error"), events)
    }

    /** Native rejection cannot fall back to a viewer action that might obscure a terminal failure. */
    @Test
    fun otherNativeFailuresNotifyWithoutOpening() {
        val events = mutableListOf<String>()
        deliverAttachmentRetryFailure(IOException("rejected"), { error("must not open") }, { events += "error" })
        assertEquals(listOf("error"), events)
    }
}
