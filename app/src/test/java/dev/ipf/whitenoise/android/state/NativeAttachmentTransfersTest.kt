package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeAttachmentTransfersTest {
    /** Only an explicit request with complete native identity can demand transfer. */
    @Test
    fun `native demand is reserved for explicit requests with exact identity`() {
        val target = NativeAttachmentTarget("11".repeat(32), "22".repeat(32), 3)

        assertTrue(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Interactive, target))
        assertFalse(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Automatic, target))
        assertFalse(shouldUseNativeExplicitDemand(AttachmentDownloadPriority.Interactive, null))
    }

    /** Native terminal and active states map without losing cancellation semantics. */
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

    /** Acquisition is owned by the subscription scope and cancellation gets a bounded non-cancellable cleanup. */
    @Test
    fun `native acquisition closes its feed and sends cancellation during coroutine cancellation`() {
        val body = source().readText().functionBody("WhiteNoiseAppState.acquireNativeAttachment")

        assertTrue(body.indexOf("feed.use") < body.indexOf("downloadAttachmentAgain"))
        assertTrue("withContext(NonCancellable)" in body)
        assertTrue("withTimeoutOrNull(NATIVE_ATTACHMENT_CANCEL_TIMEOUT_MILLIS)" in body)
        assertTrue("cancelNativeAttachment(request, target)" in body)
    }

    /** Builds one transfer status fixture for presentation mapping. */
    private fun status(state: AttachmentTransferStateFfi) =
        AttachmentTransferStatusFfi(
            reference = null,
            state = state,
            attempt = 0u,
            received = 0u,
            total = null,
            retryAt = null,
        )

    /** Locates the production transfer owner in root- and module-scoped test layouts. */
    private fun source(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentTransfers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/NativeAttachmentTransfers.kt"),
        ).firstOrNull(File::exists) ?: error("Missing NativeAttachmentTransfers.kt")
}
