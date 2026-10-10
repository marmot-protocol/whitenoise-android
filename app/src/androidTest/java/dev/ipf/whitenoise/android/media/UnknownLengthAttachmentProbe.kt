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
import org.junit.Assert.assertTrue

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
                ledger.any { it.getString("kind") == "complete" } &&
                    ledger.any { it.getString("kind") == "disconnect" }
            }
        assertUnknownLengthRequests(events, bytes.size.toLong() + 16)
        HeldAttachmentCancellationProbe.control(port, "/__acquisition-unavailable")
        repeat(3) {
            state.openNativeAttachment(request).use { local ->
                assertArrayEquals(bytes, requireNotNull(local).toByteArray())
            }
        }
        assertUnknownLengthRequests(HeldAttachmentCancellationProbe.ledger(port), bytes.size.toLong() + 16)
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

    /** Allows only MDK's header probe followed by one complete file-backed acquisition, never a duplicate body. */
    private fun assertUnknownLengthRequests(
        events: List<JSONObject>,
        ciphertextBytes: Long,
    ) {
        val gets = events.filter { it.getString("kind") == "get" }.map { it.getLong("seq") }
        assertEquals("one header probe and one file GET", 2, gets.size)
        val (probe, transfer) = gets
        val forbidden = setOf("head", "range_requested_offset", "if_range_match", "hold_timeout")
        assertTrue(events.none { it.getString("kind") in forbidden })
        val responseKinds =
            setOf("body_bytes", "status", "unknown_content_length", "range_offset", "held", "disconnect", "complete")
        assertTrue(events.filter { it.getString("kind") in responseKinds }.all { it.optLong("request") in gets })
        for (request in gets) {
            assertEquals(listOf(200L), values(events, request, "status"))
            assertEquals(listOf(0L), values(events, request, "unknown_content_length"))
            assertEquals(listOf(0L), values(events, request, "range_offset"))
        }
        assertEquals(listOf(0L), values(events, probe, "disconnect"))
        assertTrue(values(events, probe, "complete").isEmpty())
        assertTrue(values(events, probe, "held").isEmpty())
        val probeChunks = values(events, probe, "body_bytes")
        // Four paced 16-KiB fixture writes may race a header-only socket close. This is not a transport guarantee.
        assertTrue(probeChunks.all { it in 1L..FIXTURE_CHUNK_BYTES })
        assertTrue("header probe exceeded fixture scheduling allowance", probeChunks.sum() <= MAX_PROBE_BYTES)
        assertTrue(values(events, transfer, "disconnect").isEmpty())
        assertEquals(listOf(0L), values(events, transfer, "complete"))
        assertEquals(listOf(2L * 1024 * 1024), values(events, transfer, "held"))
        assertEquals(ciphertextBytes, values(events, transfer, "body_bytes").sum())
        val hold = events.single { it.getString("kind") == "hold_unknown_acquisition" }.getLong("seq")
        val held = events.single { it.getString("kind") == "held" }.getLong("seq")
        val release = events.single { it.getString("kind") == "release_acquisition" }.getLong("seq")
        val complete = events.single { it.getString("kind") == "complete" }.getLong("seq")
        assertTrue(hold < probe && probe < transfer && transfer < held && held < release && release < complete)
    }

    /** Keeps every response observation tied to the request that actually produced it. */
    private fun values(
        events: List<JSONObject>,
        request: Long,
        kind: String,
    ): List<Long> =
        events
            .filter { it.getString("kind") == kind && it.optLong("request") == request }
            .map { it.getLong("value") }

    private const val FIXTURE_CHUNK_BYTES = 16L * 1024
    private const val MAX_PROBE_BYTES = 4 * FIXTURE_CHUNK_BYTES
}
