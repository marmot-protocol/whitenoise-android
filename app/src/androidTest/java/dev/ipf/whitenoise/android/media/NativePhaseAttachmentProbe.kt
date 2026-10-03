package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.nativeProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList

/** One generated attachment as the receiving account sees it: canonical identity plus exact plaintext. */
internal class FixtureAttachment(
    val request: AttachmentTransferRequest,
    val reference: MediaAttachmentReferenceFfi,
    val bytes: ByteArray,
)

/** Records what the production progress observer publishes, without requesting or cancelling acquisition. */
internal class NativePhaseRecorder(
    scope: CoroutineScope,
    state: WhiteNoiseAppState,
    request: AttachmentTransferRequest,
) {
    private val samples = CopyOnWriteArrayList<NativeAttachmentProgress>()
    private val polled = CopyOnWriteArrayList<AttachmentTransferStateFfi>()
    private val collector =
        scope.launch(Dispatchers.Default) {
            state.nativeProgress(request).filterNotNull().collect { samples.add(it) }
        }

    // The subscription is latest-wins, so a phase shorter than one wake-up can be skipped. This read-only poll of the
    // authoritative snapshot never requests, retries or cancels work; it only widens what the probe can observe.
    private val poller =
        scope.launch(Dispatchers.IO) {
            val target =
                AttachmentLocalTargetFfi(
                    request.messageIdHex,
                    requireNotNull(request.sourceMessageIdHex),
                    request.attachmentIndex.toUInt(),
                )
            while (isActive) {
                val item =
                    state
                        .marmotIo {
                            attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target)).items
                        }.singleOrNull()
                if (item != null && polled.lastOrNull() != item.state) polled.add(item.state)
                delay(POLL_INTERVAL_MILLIS)
            }
        }

    /** Returns every phase published so far, in order. */
    fun snapshot(): List<NativeAttachmentProgress> = samples.toList()

    /** Returns each distinct consecutive authoritative phase seen by the read-only poll, in order. */
    fun polledPhases(): List<AttachmentTransferStateFfi> = polled.toList()

    /** Waits for a published sample, never inferring a phase that the native feed did not report. */
    suspend fun awaitSample(
        timeoutMillis: Long,
        predicate: (NativeAttachmentProgress) -> Boolean,
    ): NativeAttachmentProgress =
        withTimeout(timeoutMillis) {
            var match = samples.firstOrNull(predicate)
            while (match == null) {
                delay(POLL_MILLIS)
                match = samples.firstOrNull(predicate)
            }
            match
        }

    /** Ends observation; the native job and its retry budget are untouched. */
    suspend fun stop() {
        poller.cancelAndJoin()
        collector.cancelAndJoin()
    }

    private companion object {
        const val POLL_MILLIS = 10L
        const val POLL_INTERVAL_MILLIS = 2L
    }
}

/** Drives genuine paced and failing transfers through the same progress observer the conversation UI uses. */
internal object NativePhaseAttachmentProbe {
    /** Native phases that end or defer an attempt without producing a ready file. */
    private val FAILURE_PHASES =
        setOf(
            AttachmentTransferStateFfi.RETRY_SCHEDULED,
            AttachmentTransferStateFfi.FAILED,
            AttachmentTransferStateFfi.UNAVAILABLE,
            AttachmentTransferStateFfi.RETRY_EXHAUSTED,
        )

    /** A deferred attempt is still live and must be cancelled before a deliberate Retry. */
    private val LIVE_FAILURE_PHASES = setOf(AttachmentTransferStateFfi.RETRY_SCHEDULED)

    /** Runs the paced success and the permanent-miss recovery on two independent generated attachments. */
    suspend fun run(
        state: WhiteNoiseAppState,
        paced: FixtureAttachment,
        failing: FixtureAttachment,
        port: Int,
    ) = coroutineScope {
        pacedSuccess(state, paced, port)
        failureRecovery(state, failing, port)
    }

    /** A paced body exposes monotonic byte progress and every transient phase the native runtime reports. */
    private suspend fun CoroutineScope.pacedSuccess(
        state: WhiteNoiseAppState,
        attachment: FixtureAttachment,
        port: Int,
    ) {
        HeldAttachmentCancellationProbe.control(port, "/__pace-acquisition")
        val recorder = NativePhaseRecorder(this, state, attachment.request)
        try {
            withTimeout(TRANSFER_TIMEOUT_MILLIS) { readExact(state, attachment) }
            recorder.awaitSample(READY_TIMEOUT_MILLIS) { it.phase == AttachmentTransferStateFfi.READY }
        } finally {
            recorder.stop()
        }
        ControlledAttachmentProbe.report(
            scenario("known-length", recorder).put("plaintext_exact", true),
        )
    }

    /** A permanent miss is observed as a real failure phase, then recovered by exactly one deliberate Retry. */
    private suspend fun CoroutineScope.failureRecovery(
        state: WhiteNoiseAppState,
        attachment: FixtureAttachment,
        port: Int,
    ) {
        HeldAttachmentCancellationProbe.control(port, "/__acquisition-not-found")
        val recorder = NativePhaseRecorder(this, state, attachment.request)
        val outcome = JSONObject()
        try {
            val cold = async { runCatching { readExact(state, attachment) } }
            val failure = recorder.awaitSample(FAILURE_TIMEOUT_MILLIS) { it.phase in FAILURE_PHASES }
            outcome.put("failure_phase", failure.phase.name)
            if (failure.phase in LIVE_FAILURE_PHASES) {
                outcome.put("cancel_acknowledged", cancel(state, attachment.request))
            }
            val coldResult = withTimeout(CANCEL_TIMEOUT_MILLIS) { cold.await() }
            assertTrue("a failed transfer must not yield plaintext", coldResult.isFailure)
            HeldAttachmentCancellationProbe.control(port, "/__restore-acquisition")
            outcome.put("retry_accepted", retry(state, attachment.request))
            withTimeout(TRANSFER_TIMEOUT_MILLIS) { readExact(state, attachment) }
            recorder.awaitSample(READY_TIMEOUT_MILLIS) { it.phase == AttachmentTransferStateFfi.READY }
        } finally {
            recorder.stop()
        }
        ControlledAttachmentProbe.report(
            scenario("failure-recovery", recorder).put("plaintext_exact", true).also { report ->
                outcome.keys().forEach { report.put(it, outcome.get(it)) }
            },
        )
    }

    /** Joins the acquisition without granting the durable Android retry permission. */
    private suspend fun readExact(
        state: WhiteNoiseAppState,
        attachment: FixtureAttachment,
    ) = state
        .downloadAttachmentPlaintextSource(attachment.request, attachment.reference, persistInteractiveIntent = false)
        .use { assertArrayEquals(attachment.bytes, it.toByteArray()) }

    /** Requires the same native acknowledgement the Cancel control waits for. */
    private suspend fun cancel(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
    ): Boolean {
        val acknowledgement = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) {
            state.cancelAttachmentDownload(request) { acknowledgement.complete(it) }
        }
        val acknowledged = withTimeout(CANCEL_TIMEOUT_MILLIS) { acknowledgement.await() }
        assertTrue("native cancellation was not acknowledged", acknowledged)
        return acknowledged
    }

    /** Admits the visible Retry/Download again gesture once; ordinary reads never reset the native budget. */
    private suspend fun retry(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
    ): Boolean {
        val admitted = CompletableDeferred<Unit>()
        val accepted =
            withContext(Dispatchers.Main.immediate) {
                state.retryAttachmentDownload(
                    request,
                    onAccepted = { admitted.complete(Unit) },
                    onFailure = { admitted.completeExceptionally(it) },
                )
            }
        withTimeout(CANCEL_TIMEOUT_MILLIS) { admitted.await() }
        assertEquals(true, accepted)
        return accepted
    }

    /** Collapses consecutive samples of one phase and body so the report carries the transitions, not the noise. */
    private fun scenario(
        name: String,
        recorder: NativePhaseRecorder,
    ): JSONObject {
        val samples = recorder.snapshot()
        val sequence = JSONArray()
        var current: JSONObject? = null
        var key: Triple<AttachmentTransferStateFfi, ULong, ULong?>? = null
        for (sample in samples) {
            val next = Triple(sample.phase, sample.attempt, sample.total)
            if (next != key) {
                current =
                    JSONObject()
                        .put("phase", sample.phase.name)
                        .put("attempt", sample.attempt.toLong())
                        .put("total", sample.total?.toLong() ?: JSONObject.NULL)
                        .put("retry_at_present", sample.retryAt != null)
                        .put("samples", 0)
                        .put("received_min", sample.received.toLong())
                        .put("received_max", sample.received.toLong())
                        .put("monotonic", true)
                sequence.put(current)
                key = next
            }
            requireNotNull(current).apply {
                put("samples", getInt("samples") + 1)
                // A byte count that moves backwards within one body would be an untruthful progress bar.
                put("monotonic", getBoolean("monotonic") && sample.received.toLong() >= getLong("received_max"))
                put("received_min", minOf(getLong("received_min"), sample.received.toLong()))
                put("received_max", maxOf(getLong("received_max"), sample.received.toLong()))
            }
        }
        return JSONObject()
            .put("phase", "native-phases")
            .put("scenario", name)
            .put("success", true)
            .put("sequence", sequence)
            .put("polled_phases", JSONArray(recorder.polledPhases().map { it.name }))
    }

    private const val TRANSFER_TIMEOUT_MILLIS = 120_000L
    private const val READY_TIMEOUT_MILLIS = 10_000L
    private const val FAILURE_TIMEOUT_MILLIS = 60_000L
    private const val CANCEL_TIMEOUT_MILLIS = 10_000L
}
