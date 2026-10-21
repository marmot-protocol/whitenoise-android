package dev.ipf.whitenoise.android.media

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.openNativeAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/** One sample's inputs, shared by its upload, cold download and warm read. */
private class SampleContext(
    val session: FixtureSession,
    val harness: SenderHarness,
    val peers: FixturePeers,
    val receiver: WhiteNoiseAppState,
    val size: Int,
    val rep: Int,
    val marker: Int,
)

/** The phases of one cold download, as nanoseconds on the device's monotonic clock. */
private class ColdTimes(
    val start: Long,
    val lease: Long,
    val verified: Long,
    val firstProgress: Long?,
    val feedReady: Long?,
    val firstByte: Long?,
    val polled: Map<AttachmentTransferStateFfi, Long>,
)

/**
 * Measures genuine uploads, cold downloads and retained reads across sizes, with phase timings, sampled Java and native
 * peaks and per-sample ledger markers. Every sample carries only a size, a repetition and measurements: no file name,
 * identifier, URL, key or content leaves the device. It never uses a public endpoint.
 */
internal object MatrixAttachmentProbe {
    private const val MANIFEST = "matrix.json"
    private const val DEADLINE_MILLIS = 1_800_000L
    private const val READY_TIMEOUT_MILLIS = 120_000L
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val PATTERN = 251
    private const val MULTIPLIER = 31
    private const val HEX_BYTE_MASK = 0xff
    private const val MAX_SENDABLE_BYTES = 32 * 1024 * 1024
    private const val MAX_REPETITIONS = 50
    private val POST_BODY =
        setOf(
            AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
            AttachmentTransferStateFfi.DECRYPTING,
            AttachmentTransferStateFfi.VERIFYING_PLAINTEXT,
            AttachmentTransferStateFfi.READY,
        )

    /** Runs one stage; the host shapes the link between stages and force-stops only the isolated package. */
    suspend fun run() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        val stage = requireNotNull(arguments.getString("fixtureMatrixStage"))
        require(stage in setOf("foreground", "recreated"))
        val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
        val relayPort = requireNotNull(arguments.getString("fixtureRelayPort")).toInt()
        require(blobPort in 1024..65535 && relayPort in 1024..65535)
        MarmotAndroid.initialize(context)
        val role = if (stage == "foreground") "prepare" else "read"
        val restartSession = arguments.getString("fixtureRestartSession")
        val root = RestartAttachmentRetentionProbe.createRoot(context, role, restartSession)
        val relays = listOf("ws://127.0.0.1:$relayPort")
        val marmot =
            Marmot.newWithConfiguration(
                root.absolutePath,
                relays,
                MarmotOptions(
                    relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                    attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
                ),
            )
        val session = FixtureSession(context, root, marmot, relays, blobPort)
        try {
            withTimeout(DEADLINE_MILLIS) {
                marmot.start()
                if (stage == "foreground") {
                    foreground(session, plan(arguments.getString("fixtureMatrixPlan")))
                } else {
                    recreated(session)
                }
            }
        } finally {
            session.close(preserve = stage == "foreground")
        }
    }

    /** The plan is "size:repetitions" pairs, e.g. 65536:20,1048576:10, so a profile can run a bounded subset. */
    private fun plan(spec: String?): List<Pair<Int, Int>> =
        requireNotNull(spec).split(",").map { entry ->
            val (size, reps) = entry.split(":").map { it.toInt() }
            require(size in 1..MAX_SENDABLE_BYTES && reps in 1..MAX_REPETITIONS)
            size to reps
        }

    /** Sends, cold-downloads and warm-reads every planned sample, then keeps the last of each size for restart. */
    private suspend fun foreground(
        session: FixtureSession,
        plan: List<Pair<Int, Int>>,
    ) {
        val peers = session.createPeers()
        val harness =
            openSenderHarness(
                session.context,
                session.root,
                session.marmot,
                peers.sender,
                peers.group,
                session.blobPort,
                holdHostCopy = false,
            )
        val receiver = session.state(peers.receiver.label)
        val kept = JSONArray()
        var marker = 0
        try {
            withContext(Dispatchers.Main.immediate) {
                harness.controller.retryMembers()
                check(harness.controller.canSendMessages) { "generated sender membership not ready" }
            }
            for ((size, reps) in plan) {
                for (rep in 1..reps) {
                    marker += 1
                    val item = sample(SampleContext(session, harness, peers, receiver, size, rep, marker))
                    if (rep == reps) kept.put(item)
                }
            }
        } finally {
            harness.close()
        }
        HeldAttachmentCancellationProbe.control(session.blobPort, "/__marker/${marker + 1}")
        val receipt = JSONObject().put("schema", 1).put("prepare_pid", Process.myPid()).put("items", kept)
        File(session.root, MANIFEST).writeText(receipt.toString())
        ControlledAttachmentProbe.report(JSONObject().put("phase", "fixture-stage").put("stage", "matrix-prepared"))
    }

    /** One upload, one cold download and one warm retained read of a generated file; returns its restart receipt. */
    private suspend fun sample(context: SampleContext): JSONObject {
        HeldAttachmentCancellationProbe.control(context.session.blobPort, "/__marker/${context.marker}")
        val bytes = ByteArray(context.size) { ((it * MULTIPLIER + context.rep) % PATTERN).toByte() }
        val digest = sha256Hex(bytes)
        val record =
            JSONObject()
                .put("phase", "matrix-sample")
                .put("size", context.size)
                .put("rep", context.rep)
                .put("marker", context.marker)
        val reference = upload(context, bytes, record)
        context.session.marmot.catchUpAccounts()
        val request =
            MediaLifecycleAttachmentProbe
                .projectRequests(
                    context.session.marmot,
                    context.peers.receiver,
                    context.peers.group,
                    listOf(reference),
                ).single()
        cold(context, request, reference, digest, record)
        warm(context, request, reference, digest, record)
        ControlledAttachmentProbe.report(record)
        return JSONObject().put("size", context.size).put("sha256", digest).put("request", request.toJson())
    }

    /** Splits the shipping send into its synchronous preparation half and its upload-and-publish half. */
    private suspend fun upload(
        context: SampleContext,
        bytes: ByteArray,
        record: JSONObject,
    ): MediaAttachmentReferenceFfi {
        val name = "matrix-${context.marker}.bin"
        val controller = context.harness.controller
        var reference: MediaAttachmentReferenceFfi? = null
        ControlledAttachmentProbe.measure("matrix-upload-${context.marker}", context.size) {
            val prepStart = SystemClock.elapsedRealtimeNanos()
            val attachment = PendingAttachment(bytes, "application/octet-stream", name)
            val seeded =
                withContext(Dispatchers.Main.immediate) { controller.queueAttachments(listOf(attachment), null) }
            val prepEnd = SystemClock.elapsedRealtimeNanos()
            withContext(Dispatchers.Main.immediate) { controller.uploadQueued(requireNotNull(seeded)) }
            val uploadEnd = SystemClock.elapsedRealtimeNanos()
            val sender = context.peers.sender.label
            val published =
                awaitAndroidFixtureReferences(context.session.marmot, sender, context.peers.group, listOf(name))
            reference = published.references.single()
            val referenceEnd = SystemClock.elapsedRealtimeNanos()
            record
                .put("prep_visible_ms", millis(prepStart, prepEnd))
                .put("upload_publish_ms", millis(prepEnd, uploadEnd))
                .put("reference_ms", millis(uploadEnd, referenceEnd))
        }
        return requireNotNull(reference)
    }

    /** The cold download with the production observer and an authoritative poll, so each phase has a time. */
    private suspend fun cold(
        context: SampleContext,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        digest: String,
        record: JSONObject,
    ) {
        val times =
            coroutineScope {
                val recorder = NativePhaseRecorder(this, context.receiver, request)
                var lease = 0L
                var verified = 0L
                val start = SystemClock.elapsedRealtimeNanos()
                ControlledAttachmentProbe.measure("matrix-cold-${context.marker}", context.size) {
                    val source =
                        context.receiver.downloadAttachmentPlaintextSource(
                            request,
                            reference,
                            persistInteractiveIntent = false,
                        )
                    source.use {
                        lease = SystemClock.elapsedRealtimeNanos()
                        assertEquals(digest, digestOf(it))
                        verified = SystemClock.elapsedRealtimeNanos()
                    }
                }
                recorder.awaitSample(READY_TIMEOUT_MILLIS) { it.phase == AttachmentTransferStateFfi.READY }
                recorder.stop()
                val feed = recorder.snapshot().zip(recorder.sampleTimesNanos())
                val first =
                    feed
                        .firstOrNull { (sample, _) ->
                            sample.phase == AttachmentTransferStateFfi.DOWNLOADING && sample.received > 0uL
                        }?.second
                val feedReady =
                    feed.firstOrNull { (sample, _) -> sample.phase == AttachmentTransferStateFfi.READY }?.second
                val polled = recorder.polledPhases().zip(recorder.polledTimesNanos()).toMap()
                ColdTimes(start, lease, verified, first, feedReady, recorder.firstByteNanos(), polled)
            }
        record
            .put("admission_ms", phaseMillis(times, times.polled[AttachmentTransferStateFfi.DOWNLOADING]))
            .put("first_progress_ms", phaseMillis(times, times.firstByte))
            .put("feed_first_progress_ms", phaseMillis(times, times.firstProgress))
            .put("feed_ready_ms", phaseMillis(times, times.feedReady))
            .put("body_complete_ms", phaseMillis(times, POST_BODY.mapNotNull { times.polled[it] }.minOrNull()))
            .put("ready_ms", phaseMillis(times, times.polled[AttachmentTransferStateFfi.READY]))
            .put("lease_ms", millis(times.start, times.lease))
            .put("digest_ms", millis(times.lease, times.verified))
    }

    /** A second read of the same attachment in the same process, served from native retention. */
    private suspend fun warm(
        context: SampleContext,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        digest: String,
        record: JSONObject,
    ) {
        var leaseMillis = 0.0
        ControlledAttachmentProbe.measure("matrix-warm-${context.marker}", context.size) {
            val start = SystemClock.elapsedRealtimeNanos()
            val source =
                context.receiver.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
            source.use {
                leaseMillis = millis(start, SystemClock.elapsedRealtimeNanos())
                assertEquals(digest, digestOf(it))
            }
        }
        record.put("warm_lease_ms", leaseMillis)
    }

    /** Reads every kept attachment in a new process with acquisition made unavailable by the host. */
    private suspend fun recreated(session: FixtureSession) {
        val manifest = JSONObject(File(session.root, MANIFEST).readText())
        check(manifest.getInt("schema") == 1)
        val previousPid = manifest.getInt("prepare_pid")
        assertNotEquals("fixture did not cross an Android process boundary", previousPid, Process.myPid())
        val items = manifest.getJSONArray("items")
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val request = requestFrom(item.getJSONObject("request"))
            val size = item.getInt("size")
            val digest = item.getString("sha256")
            if (request.accountRef !in session.accounts) session.accounts += request.accountRef
            val state = session.state(request.accountRef)
            var nativeLease = 0.0
            ControlledAttachmentProbe.measure("matrix-recreated-native-$size", size) {
                val start = SystemClock.elapsedRealtimeNanos()
                requireNotNull(state.openNativeAttachment(request)).use {
                    nativeLease = millis(start, SystemClock.elapsedRealtimeNanos())
                    assertEquals(digest, digestOf(it))
                }
            }
            val reference = MediaLifecycleAttachmentProbe.publishedReference(session.marmot, request)
            ControlledAttachmentProbe.measure("matrix-recreated-resolver-$size", size) {
                state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false).use {
                    assertEquals(digest, digestOf(it))
                }
            }
            ControlledAttachmentProbe.report(
                JSONObject().put("phase", "matrix-recreated").put("size", size).put("native_lease_ms", nativeLease),
            )
        }
        val complete = JSONObject().put("phase", "matrix-recreated-complete")
        ControlledAttachmentProbe.report(complete.put("items", items.length()))
    }

    /** Milliseconds between two monotonic readings. */
    private fun millis(
        from: Long,
        to: Long,
    ): Double = (to - from) / NANOS_PER_MILLI

    /** Milliseconds from the download's start to a phase, or null when that phase was never observed. */
    private fun phaseMillis(
        times: ColdTimes,
        at: Long?,
    ): Any = at?.let { millis(times.start, it) } ?: JSONObject.NULL

    /** Streams a lease through SHA-256 without a second full-size allocation. */
    private suspend fun digestOf(local: AttachmentPlaintext): String {
        val digest = MessageDigest.getInstance("SHA-256")
        local.copyTo(
            object : OutputStream() {
                override fun write(value: Int) = digest.update(value.toByte())

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = digest.update(bytes, offset, length)
            },
        )
        return digest.digest().toHex()
    }

    /** Hex digest of generated plaintext. */
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Lowercase hex without allocating a string per byte. */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }

    /** Keeps display and source identity distinct across the process boundary. */
    private fun AttachmentTransferRequest.toJson(): JSONObject =
        JSONObject()
            .put("account", accountRef)
            .put("group", groupIdHex)
            .put("message", messageIdHex)
            .put("source", requireNotNull(sourceMessageIdHex))
            .put("index", attachmentIndex)

    /** Restores one request from the private receipt. */
    private fun requestFrom(value: JSONObject) =
        AttachmentTransferRequest(
            value.getString("account"),
            value.getString("group"),
            value.getString("message"),
            value.getInt("index"),
            value.getString("source"),
        )
}
