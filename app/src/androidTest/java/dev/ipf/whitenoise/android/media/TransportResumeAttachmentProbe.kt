package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
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
import org.junit.Assert.assertTrue

/** Interrupts a genuine ciphertext body without granting a deliberate Retry or seeding a checkpoint. */
internal object TransportResumeAttachmentProbe {
    /** Native retry must reuse compatible bytes, or replace an incompatible representation before publication. */
    suspend fun run(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        port: Int,
        bytes: ByteArray,
        scenario: String,
    ) = coroutineScope {
        val changed = scenario == "changed-validator"
        HeldAttachmentCancellationProbe.control(port, "/__hold-resumable-acquisition")
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
        assertEquals((bytes.size + 16).toULong(), requireNotNull(progress).total)
        HeldAttachmentCancellationProbe.awaitLedger(port) { events -> events.any { it.getString("kind") == "held" } }
        assertNull("partial ciphertext must not become a readable plaintext lease", state.openNativeAttachment(request))
        val path = if (changed) "/__interrupt-changed-validator" else "/__interrupt-acquisition"
        HeldAttachmentCancellationProbe.control(port, path)
        withTimeout(90_000L) { read.await() }
        val events =
            HeldAttachmentCancellationProbe.awaitLedger(port) { ledger ->
                ledger.any { it.getString("kind") == "disconnect" } && ledger.any { it.getString("kind") == "complete" }
            }
        assertEquals(2, events.count { it.getString("kind") == "get" })
        assertEquals(0, events.count { it.getString("kind") == "head" })
        val prefix = 2L * 1024 * 1024
        assertEquals(prefix, events.single { it.getString("kind") == "range_requested_offset" }.getLong("value"))
        assertEquals(if (changed) 0 else 1, events.single { it.getString("kind") == "if_range_match" }.getInt("value"))
        val expectedTransferred = bytes.size + 16L + if (changed) prefix else 0L
        assertEquals(
            expectedTransferred,
            events.filter { it.getString("kind") == "body_bytes" }.sumOf { it.getLong("value") },
        )
        HeldAttachmentCancellationProbe.control(port, "/__acquisition-unavailable")
        repeat(3) {
            state.openNativeAttachment(request).use { local ->
                assertArrayEquals(bytes, requireNotNull(local).toByteArray())
            }
        }
        assertTrue(HeldAttachmentCancellationProbe.ledger(port).count { it.getString("kind") == "get" } == 2)
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "transport-resume")
                .put("success", true)
                .put("scenario", scenario)
                .put("held_prefix_bytes", prefix)
                .put("plaintext_exact", true)
                .put("partial_plaintext_unavailable", true)
                .put("deliberate_retry_used", false)
                .put("platform_job_stop_qualified", false)
                .put("android_process_restart_qualified", false),
        )
    }
}
