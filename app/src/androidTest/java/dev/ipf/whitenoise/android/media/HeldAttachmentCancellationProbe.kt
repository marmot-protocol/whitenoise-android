package dev.ipf.whitenoise.android.media

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentDemandIntent
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cancelNativeAttachmentBounded
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.nativeProgress
import dev.ipf.whitenoise.android.state.requestNativeInteractiveAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.net.HttpURLConnection
import java.net.URL

/** Proves native acknowledgement, socket closure and a quiet interval independently of host waiter disposal. */
internal object HeldAttachmentCancellationProbe {
    /** Cancels a real held ciphertext body, rejects ordinary retries, then admits one deliberate Retry. */
    suspend fun run(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        port: Int,
        bytes: ByteArray,
    ) = coroutineScope {
        assertNoWorkCancellation(state, request)
        control(port, "/__hold-acquisition")
        val cold = async { runCatching { read(state, request, reference).close() } }
        val progress =
            withTimeout(5_000L) {
                // DOWNLOADING may precede response headers; this fixture declares a known 1040-byte body.
                state.nativeProgress(request).first {
                    it?.phase == AttachmentTransferStateFfi.DOWNLOADING && it.total == 1040uL
                }
            }
        assertEquals(1040uL, requireNotNull(progress).total)
        awaitLedger(port) { events -> events.any { it.getString("kind") == "held" } }
        assertActiveJoins(state, request)
        control(port, "/__cancel-marker")
        val started = SystemClock.elapsedRealtimeNanos()
        val acknowledgement = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) {
            state.cancelAttachmentDownload(request) { acknowledgement.complete(it) }
        }
        assertTrue(withTimeout(5_000L) { acknowledgement.await() })
        val ackMillis = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        val disconnected = awaitLedger(port) { events -> events.any { it.getString("kind") == "disconnect" } }
        val marker = disconnected.single { it.getString("kind") == "cancel_marker" }.getLong("at_ns")
        val closed = disconnected.single { it.getString("kind") == "disconnect" }.getLong("at_ns")
        val socketMillis = (closed - marker) / 1_000_000.0
        assertTrue("native socket did not close within the cancellation budget", socketMillis in 0.0..5_000.0)
        assertTrue(withTimeout(5_000L) { cold.await() }.isFailure)
        val target = AttachmentLocalTargetFfi(request.messageIdHex, requireNotNull(request.sourceMessageIdHex), 0u)
        val terminal =
            state.marmotIo {
                attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target)).items.single().state
            }
        assertEquals(AttachmentTransferStateFfi.CANCELLED, terminal)
        repeat(10) {
            assertTrue(runCatching { read(state, request, reference).close() }.isFailure)
        }
        delay(30_000L)
        val quiet = ledger(port)
        assertEquals(1, quiet.count { it.getString("kind") in setOf("get", "head") })
        control(port, "/__release-acquisition")
        val admitted = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main.immediate) {
            val accepted =
                state.retryAttachmentDownload(
                    request,
                    onAccepted = { admitted.complete(Unit) },
                    onFailure = { admitted.completeExceptionally(it) },
                )
            assertTrue(accepted)
        }
        withTimeout(5_000L) { admitted.await() }
        withTimeout(10_000L) {
            read(state, request, reference).use { assertArrayEquals(bytes, it.toByteArray()) }
        }
        report(ackMillis, socketMillis)
    }

    /** Ten live joins keep body identity and retry time; durable budgets are checked at their native owner. */
    private suspend fun assertActiveJoins(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
    ) {
        val target = AttachmentLocalTargetFfi(request.messageIdHex, requireNotNull(request.sourceMessageIdHex), 0u)
        state.marmotIo {
            /** Reads the same canonical target before and after each real join. */
            suspend fun snapshot() = attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target))
            val before = snapshot().items.single()
            assertEquals(AttachmentTransferStateFfi.DOWNLOADING, before.state)
            repeat(10) {
                requestNativeInteractiveAttachment(
                    request.accountRef,
                    request.groupIdHex,
                    target,
                    AttachmentDemandIntent.Join,
                )
                val after = snapshot().items.single()
                assertEquals(before.reference, after.reference)
                assertEquals(before.attempt, after.attempt)
                assertEquals(before.retryAt, after.retryAt)
                assertEquals(before.state, after.state)
            }
        }
    }

    /** Emits only measured deadlines and closed assertions, excluding generated identity and locator metadata. */
    private fun report(
        ackMillis: Double,
        socketMillis: Double,
    ) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(
                    "controlled_attachment_json",
                    JSONObject()
                        .put("phase", "held-body-cancellation")
                        .put("success", true)
                        .put("ack_elapsed_ms", ackMillis)
                        .put("socket_close_elapsed_ms", socketMillis)
                        .put("quiet_seconds", 30)
                        .put("ordinary_terminal_joins", 10)
                        .put("active_joins", 10)
                        .put("no_work_cancel_confirmed", true)
                        .put("deliberate_retry_exact_bytes", true)
                        .toString(),
                )
            },
        )
    }

    /** A canonical pre-admission snapshot proves that successful cancellation means no active native work. */
    private suspend fun assertNoWorkCancellation(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
    ) {
        val target =
            AttachmentLocalTargetFfi(
                request.messageIdHex,
                requireNotNull(request.sourceMessageIdHex),
                request.attachmentIndex.toUInt(),
            )
        val beforeAdmission =
            state.marmotIo {
                attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target)).items.single().state
            }
        assertEquals(AttachmentTransferStateFfi.NOT_REQUESTED, beforeAdmission)
        assertTrue(
            "canonical pre-admission cancellation was not confirmed",
            state.cancelNativeAttachmentBounded(request),
        )
    }

    /** Keeps each ordinary read a join with no durable Android retry permission. */
    private suspend fun read(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
    ) = state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)

    /** Uses only the explicitly generated loopback server and bounds each transport operation. */
    internal suspend fun control(
        port: Int,
        path: String,
    ) = http(port, path, "POST")

    /** Reads synthetic request events without exporting account ids or ciphertext locators. */
    internal suspend fun ledger(port: Int): List<JSONObject> {
        val events = JSONArray(http(port, "/__ledger", "GET"))
        return List(events.length()) { events.getJSONObject(it) }
    }

    /** Waits for the durable event itself rather than assuming a fixed ledger flush delay. */
    internal suspend fun awaitLedger(
        port: Int,
        done: (List<JSONObject>) -> Boolean,
    ): List<JSONObject> =
        withTimeout(5_000L) {
            var events = ledger(port)
            while (!done(events)) {
                delay(10L)
                events = ledger(port)
            }
            events
        }

    /** Disconnects the diagnostic control connection on success, timeout and assertion failure. */
    private suspend fun http(
        port: Int,
        path: String,
        method: String,
    ): String =
        withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                check(connection.responseCode == 200)
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
}
