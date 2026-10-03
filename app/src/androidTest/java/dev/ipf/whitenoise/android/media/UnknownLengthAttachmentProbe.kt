package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.nativeProgress
import dev.ipf.whitenoise.android.state.openNativeAttachment
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** Verifies native byte-only progress when the genuine ciphertext response has no Content-Length. */
internal object UnknownLengthAttachmentProbe {
    /** A declared plaintext size cannot become an invented ciphertext total or premature plaintext lease. */
    suspend fun run(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        port: Int,
        bytes: ByteArray,
        onProgress: ((NativeAttachmentProgress, MediaAttachmentReferenceFfi) -> Unit)? = null,
    ) = coroutineScope {
        HeldAttachmentCancellationProbe.control(port, "/__hold-unknown-acquisition")
        val read =
            async {
                state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false).use {
                    assertArrayEquals(bytes, it.toByteArray())
                }
            }
        val progress =
            withTimeout(10_000L) {
                state.nativeProgress(request).first {
                    it?.phase == AttachmentTransferStateFfi.DOWNLOADING && it.received >= 1024uL * 1024uL
                }
            }
        assertNull("the HTTP total is unknown", requireNotNull(progress).total)
        assertNull("unknown-length progress cannot report a percentage", progress.fraction)
        HeldAttachmentCancellationProbe.awaitLedger(port) { events -> events.any { it.getString("kind") == "held" } }
        assertNull("held ciphertext cannot publish plaintext", state.openNativeAttachment(request))
        onProgress?.invoke(progress, reference)
        HeldAttachmentCancellationProbe.control(port, "/__release-acquisition")
        withTimeout(30_000L) { read.await() }
        val events =
            HeldAttachmentCancellationProbe.awaitLedger(port) { ledger ->
                ledger.any { it.getString("kind") == "complete" }
            }
        assertEquals(1, events.count { it.getString("kind") == "get" })
        assertEquals(0, events.count { it.getString("kind") == "head" })
        assertEquals(1, events.count { it.getString("kind") == "unknown_content_length" })
        HeldAttachmentCancellationProbe.control(port, "/__acquisition-unavailable")
        repeat(3) {
            state.openNativeAttachment(request).use { local ->
                assertArrayEquals(bytes, requireNotNull(local).toByteArray())
            }
        }
        assertEquals(1, HeldAttachmentCancellationProbe.ledger(port).count { it.getString("kind") == "get" })
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "unknown-length")
                .put("success", true)
                .put("total_unknown", true)
                .put("fraction_unknown", true)
                .put("platform_progress_semantics_qualified", onProgress != null)
                .put("observed_received_bytes", progress.received.toLong())
                .put("partial_plaintext_unavailable", true)
                .put("plaintext_exact", true)
                .put("retained_reads", 3),
        )
    }
}
