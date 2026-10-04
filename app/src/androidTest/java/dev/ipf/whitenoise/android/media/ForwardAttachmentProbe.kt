package dev.ipf.whitenoise.android.media

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.hasNativeAttachment
import dev.ipf.whitenoise.android.state.isForwardOwnerSignedIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** The generated peers and groups of one forward matrix: the author writes, the forwarder forwards. */
private class ForwardPeers(
    val author: AccountSummaryFfi,
    val forwarder: AccountSummaryFfi,
    val source: String,
    val ownSource: String,
    val destination: String,
)

/** One forwardable source attachment as the forwarder's own projection sees it. */
private class ForwardSource(
    val group: String,
    val request: AttachmentTransferRequest,
    val reference: MediaAttachmentReferenceFfi,
    val bytes: ByteArray,
)

/** The closed phase durations parsed from one forward's local diagnostics lines. */
private class ForwardPhases(
    val lines: List<String>,
) {
    /** Lines for one phase name, in emission order. */
    private fun of(phase: String): List<String> = lines.filter { " phase=$phase " in it }

    /** The one line for [phase], or null when it was never emitted or was emitted more than once. */
    private fun single(phase: String): String? = of(phase).singleOrNull()

    /** The `duration_ms` of the single line for [phase], or JSON null when it was never emitted. */
    fun duration(phase: String): Any = single(phase)?.let { field(it, "duration_ms").toLong() } ?: JSONObject.NULL

    /** The `result` of the single line for [phase], or JSON null when it was never emitted. */
    fun result(phase: String): Any = single(phase)?.let { field(it, "result") } ?: JSONObject.NULL

    /** How many times [phase] was emitted; a start phase emitted twice means a retried attempt. */
    fun count(phase: String): Int = of(phase).size

    /** Reads one `key=value` field from a schema line. */
    private fun field(
        line: String,
        key: String,
    ): String = line.substringAfter(" $key=").substringBefore(' ')
}

/** Everything one forward sample needs besides its source and repetition. */
private class ForwardMatrixContext(
    val session: FixtureSession,
    val peers: ForwardPeers,
    val state: WhiteNoiseAppState,
    val seen: MutableSet<String>,
)

/**
 * Forwards one generated 1 KiB `text/plain` attachment from a source chat into a destination chat through the
 * shipping `startForwardMessages` path, five times each with the source never opened, opened once (native retention
 * only) and genuinely host-cached (the forwarder's own send), beside five direct sends of the same bytes. Every
 * forward records the local phase timings the app itself emits, the destination's genuine receipt, and a ledger
 * marker so the host can attribute each request to one sample. Nothing here names a file, id, URL or key.
 */
internal object ForwardAttachmentProbe {
    private const val PAYLOAD_BYTES = 1024
    private const val REPETITIONS = 5
    private const val DEADLINE_MILLIS = 900_000L
    private const val FORWARD_TIMEOUT_MILLIS = 300_000L
    private const val PROJECTION_TIMEOUT_MILLIS = 60_000L
    private const val POLL_MILLIS = 20L
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val PATTERN = 251
    private const val HISTORY_LIMIT = 100u
    private const val OPERATION = " op=message_forward "

    // Ledger marker bases shared with the host checker: the five samples of a stage use base + 1..5, so direct
    // sends are 1..5 and cached forwards are 31..35, and the one acquisition that retains the source uses 20 itself.
    // Both source sends happen before the first marker, so the host attributes them to the setup region.
    private const val MARKER_DIRECT_BASE = 0
    private const val MARKER_UNCACHED_BASE = 10
    private const val MARKER_RETAINED_BASE = 20
    private const val MARKER_CACHED_BASE = 30
    private const val MARKER_FINAL = 40

    /** Runs the whole matrix in one process against the loopback relay and blob server. */
    suspend fun run() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        require(arguments.getString("fixtureForward") == "true")
        val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
        val relayPort = requireNotNull(arguments.getString("fixtureRelayPort")).toInt()
        require(blobPort in 1024..65535 && relayPort in 1024..65535)
        MarmotAndroid.initialize(context)
        val root = RestartAttachmentRetentionProbe.createRoot(context, null, null)
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
                matrix(session)
            }
        } finally {
            PerformanceDiagnostics.stop()
            session.close(preserve = false)
        }
    }

    /**
     * Creates the peers and three groups, sends both sources, then runs the direct baseline and every variant. The
     * forwarder's own source is sent before the forwarder's app state exists, because a process has one app state and
     * one host-cache index, and this fixture's second instance must see the copy the shipping publication wrote.
     */
    private suspend fun matrix(session: FixtureSession) {
        val peers = createPeers(session)
        val authorSource = sendAuthorSource(session, peers)
        val ownSent = sendOwnSource(session, peers, authorSource.bytes)
        val harness =
            openSenderHarness(
                session.context,
                session.root,
                session.marmot,
                peers.forwarder,
                peers.destination,
                session.blobPort,
                holdHostCopy = false,
            )
        try {
            val state = harness.state
            check(state.isForwardOwnerSignedIn(peers.forwarder.label)) { "generated forwarder is not a signing owner" }
            withContext(Dispatchers.Main.immediate) {
                harness.controller.retryMembers()
                check(harness.controller.canSendMessages) { "generated forwarder membership not ready" }
            }
            val seen = mutableSetOf<String>()
            directBaseline(session, peers, harness, authorSource.bytes, seen)
            val context = ForwardMatrixContext(session, peers, state, seen)
            assertSourceState(state, authorSource, memory = false, hostDisk = false, native = false)
            forwardVariant(context, authorSource, "uncached", MARKER_UNCACHED_BASE)
            openOnce(state, authorSource, MARKER_RETAINED_BASE, session.blobPort)
            assertSourceState(state, authorSource, memory = false, hostDisk = false, native = true)
            forwardVariant(context, authorSource, "retained", MARKER_RETAINED_BASE)
            val ownSource = ownSourceFor(peers, ownSent, authorSource.bytes, state)
            assertSourceState(state, ownSource, memory = false, hostDisk = true, native = true)
            forwardVariant(context, ownSource, "cached", MARKER_CACHED_BASE)
            HeldAttachmentCancellationProbe.control(session.blobPort, "/__marker/$MARKER_FINAL")
            reportDestinationTimeline(session.marmot, peers)
        } finally {
            harness.close()
        }
        ControlledAttachmentProbe.report(JSONObject().put("phase", "fixture-stage").put("stage", "forward-complete"))
    }

    /** Two disposable identities and three groups: the author's source, the forwarder's own source, the destination. */
    private suspend fun createPeers(session: FixtureSession): ForwardPeers {
        val base = session.createPeers()
        val marmot = session.marmot
        val forwarder = base.receiver
        val author = base.sender
        val members = listOf(author.accountIdHex)
        val ownSource = marmot.createGroup(forwarder.label, "Generated own source", members, null)
        ControlledAttachmentProbe.awaitReceivedGroup(marmot, author.label, ownSource)
        val destination = marmot.createGroup(forwarder.label, "Generated destination", members, null)
        ControlledAttachmentProbe.awaitReceivedGroup(marmot, author.label, destination)
        marmot.catchUpAccounts()
        return ForwardPeers(author, forwarder, base.group, ownSource, destination)
    }

    /** The author sends the generated 1 KiB text through the shipping controller; the forwarder projects it. */
    private suspend fun sendAuthorSource(
        session: FixtureSession,
        peers: ForwardPeers,
    ): ForwardSource {
        val bytes = ByteArray(PAYLOAD_BYTES) { (it * 7 % PATTERN).toByte() }
        val sent =
            sendAndroidFixtureMedia(
                session.context,
                session.root,
                session.marmot,
                peers.author,
                peers.source,
                session.blobPort,
                listOf(PendingAttachment(bytes, "text/plain", "source.txt")),
            )
        session.marmot.catchUpAccounts()
        val request =
            MediaLifecycleAttachmentProbe
                .projectRequests(session.marmot, peers.forwarder, peers.source, sent.references)
                .single()
        val reference = MediaLifecycleAttachmentProbe.publishedReference(session.marmot, request)
        return ForwardSource(peers.source, request, reference, bytes)
    }

    /**
     * The forwarder sends the same bytes into its own source group and waits for the shipping own-copy publication,
     * so this source is genuinely host-cached rather than seeded.
     */
    private suspend fun sendOwnSource(
        session: FixtureSession,
        peers: ForwardPeers,
        bytes: ByteArray,
    ): SentFixtureMessage {
        val sent =
            sendAndroidFixtureMedia(
                session.context,
                session.root,
                session.marmot,
                peers.forwarder,
                peers.ownSource,
                session.blobPort,
                listOf(PendingAttachment(bytes.copyOf(), "text/plain", "own-source.txt")),
                awaitOwnHostPublication = true,
            )
        check(sent.ownHostCached.single()) { "own send did not publish its encrypted host copy" }
        return sent
    }

    /** The own send as the forwarder's app state sees it, with the host copy visible after that state hydrates. */
    private suspend fun ownSourceFor(
        peers: ForwardPeers,
        sent: SentFixtureMessage,
        bytes: ByteArray,
        state: WhiteNoiseAppState,
    ): ForwardSource {
        val request =
            AttachmentTransferRequest(
                peers.forwarder.label,
                peers.ownSource,
                sent.messageIdHex,
                0,
                sent.sourceMessageIdHex,
            )
        check(state.hasHostCachedAttachmentAfterHydration(request)) { "forwarder state cannot see its own host copy" }
        return ForwardSource(peers.ownSource, request, sent.references.single(), bytes)
    }

    /** Five direct sends of the same bytes into the destination through the shipping controller. */
    private suspend fun directBaseline(
        session: FixtureSession,
        peers: ForwardPeers,
        harness: SenderHarness,
        bytes: ByteArray,
        seen: MutableSet<String>,
    ) {
        for (rep in 1..REPETITIONS) {
            HeldAttachmentCancellationProbe.control(session.blobPort, "/__marker/${MARKER_DIRECT_BASE + rep}")
            val name = "direct-$rep.txt"
            var sendMillis = 0.0
            var referenceMillis = 0.0
            ControlledAttachmentProbe.measure("forward-direct-$rep", PAYLOAD_BYTES) {
                val started = SystemClock.elapsedRealtimeNanos()
                val attachment = PendingAttachment(bytes.copyOf(), "text/plain", name)
                withContext(Dispatchers.Main.immediate) {
                    harness.controller.sendAttachments(listOf(attachment), caption = null)
                }
                val sentAt = SystemClock.elapsedRealtimeNanos()
                val published =
                    awaitAndroidFixtureReferences(
                        session.marmot,
                        peers.forwarder.label,
                        peers.destination,
                        listOf(name),
                    )
                seen += published.references.single().ciphertextSha256
                sendMillis = millis(started, sentAt)
                referenceMillis = millis(sentAt, SystemClock.elapsedRealtimeNanos())
            }
            ControlledAttachmentProbe.report(
                JSONObject()
                    .put("phase", "forward-direct")
                    .put("rep", rep)
                    .put("payload_bytes", PAYLOAD_BYTES)
                    .put("send_ms", sendMillis)
                    .put("reference_ms", referenceMillis),
            )
        }
    }

    /** Opens the source once through the unchanged resolver, which acquires it into native retention only. */
    private suspend fun openOnce(
        state: WhiteNoiseAppState,
        source: ForwardSource,
        marker: Int,
        blobPort: Int,
    ) {
        HeldAttachmentCancellationProbe.control(blobPort, "/__marker/$marker")
        ControlledAttachmentProbe.measure("forward-source-open", PAYLOAD_BYTES) {
            val opened =
                state.downloadAttachmentPlaintextSource(
                    source.request,
                    source.reference,
                    persistInteractiveIntent = false,
                )
            opened.use { assertArrayEquals(source.bytes, it.toByteArray()) }
        }
    }

    /** Fails the run unless each local layer that could serve the source is in the declared state. */
    private suspend fun assertSourceState(
        state: WhiteNoiseAppState,
        source: ForwardSource,
        memory: Boolean,
        hostDisk: Boolean,
        native: Boolean,
    ) {
        val observed = sourceState(state, source)
        assertEquals("memory cache state", memory, observed.getBoolean("source_memory_cached"))
        assertEquals("host disk state", hostDisk, observed.getBoolean("source_host_disk_cached"))
        assertEquals("native retention state", native, observed.getBoolean("source_native_retained"))
    }

    /** Reads the memory, host-disk and native state of one source without changing any of them. */
    private suspend fun sourceState(
        state: WhiteNoiseAppState,
        source: ForwardSource,
    ): JSONObject {
        val key = source.request.cacheKey()
        val memory = withContext(Dispatchers.Main.immediate) { state.cachedMediaPlaintext(key) } != null
        val hostDisk = withContext(Dispatchers.IO) { state.diskMediaCache.containsAfterHydration(key) }
        val native = state.hasNativeAttachment(source.request)
        return JSONObject()
            .put("source_memory_cached", memory)
            .put("source_host_disk_cached", hostDisk)
            .put("source_native_retained", native)
    }

    /** Five forwards of one source, each with its own marker, diagnostics session and genuine receipt check. */
    private suspend fun forwardVariant(
        context: ForwardMatrixContext,
        source: ForwardSource,
        variant: String,
        markerBase: Int,
    ) {
        for (rep in 1..REPETITIONS) {
            forwardOnce(context, source, variant, rep, markerBase + rep)
        }
    }

    /** One forward through the shipping path, from acceptance to the author's exact read of the delivered copy. */
    @Suppress("LongMethod") // One sample's timing, phase, receipt and ledger evidence stays in one auditable sequence.
    private suspend fun forwardOnce(
        context: ForwardMatrixContext,
        source: ForwardSource,
        variant: String,
        rep: Int,
        marker: Int,
    ) {
        val state = context.state
        val peers = context.peers
        HeldAttachmentCancellationProbe.control(context.session.blobPort, "/__marker/$marker")
        val row =
            JSONObject()
                .put("phase", "forward-sample")
                .put("variant", variant)
                .put("rep", rep)
                .put("marker", marker)
                .put("payload_bytes", PAYLOAD_BYTES)
        val before = sourceState(state, source)
        before.keys().forEach { key -> row.put(key, before.get(key)) }
        PerformanceDiagnostics.stop()
        check(PerformanceDiagnostics.start().active) { "local diagnostics are unavailable in this build" }
        val payload =
            ForwardMessagePayload.Media(
                sourceGroupIdHex = source.group,
                sourceMessageIdHex = source.request.messageIdHex,
                caption = null,
                attachments = listOf(ForwardAttachmentSource(0, source.reference)),
            )
        var terminal: ForwardOperationSnapshot? = null
        var totalMillis = 0.0
        var deliveredMillis = 0.0
        var deliveredExact = false
        ControlledAttachmentProbe.measure("forward-$variant-$rep", PAYLOAD_BYTES) {
            val started = SystemClock.elapsedRealtimeNanos()
            val accepted =
                withContext(Dispatchers.Main.immediate) {
                    state.startForwardMessages(
                        targetGroupIds = listOf(peers.destination),
                        messages = listOf(payload),
                        sourceAccountRef = peers.forwarder.label,
                        destinationAccountRef = peers.forwarder.label,
                    )
                }
            check(accepted) { "the shipping forward path rejected the generated request" }
            val snapshot = awaitTerminal(state)
            totalMillis = millis(started, SystemClock.elapsedRealtimeNanos())
            terminal = snapshot
            assertEquals(ForwardOperationPhase.Completed, snapshot.phase)
            val delivered = awaitNewDestinationReference(context.session.marmot, peers, context.seen)
            val authorRequest = awaitAuthorProjection(context.session.marmot, peers, delivered)
            deliveredMillis = millis(started, SystemClock.elapsedRealtimeNanos())
            context.session
                .state(peers.author.label)
                .downloadAttachmentPlaintextSource(authorRequest, delivered, persistInteractiveIntent = false)
                .use { deliveredExact = source.bytes.contentEquals(it.toByteArray()) }
            assertTrue("destination copy differs from the source bytes", deliveredExact)
        }
        val phases = ForwardPhases(awaitForwardLines())
        withContext(Dispatchers.Main.immediate) { state.dismissActiveForwardOperation() }
        val snapshot = requireNotNull(terminal)
        val target = snapshot.targets.single()
        ControlledAttachmentProbe.report(
            row
                .put("success", true)
                .put("total_ms", totalMillis)
                .put("delivered_ms", deliveredMillis)
                .put("delivered_exact", deliveredExact)
                .put("terminal", snapshot.phase.name)
                .put("sent_messages", target.sentMessages)
                .put("uploaded_attachments", target.uploadedAttachments)
                .put("source_lookup_hit", phases.result("forward_source_lookup") == "success")
                .put("source_lookup_ms", phases.duration("forward_source_lookup"))
                .put("source_reference_resolved_ms", phases.duration("forward_source_reference_resolved"))
                .put("source_download_ms", phases.duration("forward_source_download_return"))
                .put("source_download_result", phases.result("forward_source_download_return"))
                .put("source_ready_ms", phases.duration("forward_source_ready"))
                .put("upload_ms", phases.duration("media_upload_return"))
                .put("upload_attempts", phases.count("media_upload_start"))
                .put("commit_lock_wait_ms", phases.duration("commit_lock_acquired"))
                .put("publish_ms", phases.duration("media_publish_return"))
                .put("publish_attempts", phases.count("media_publish_start"))
                .put("convergence_attempts", phases.count("convergence_start"))
                .put("complete_result", phases.result("forward_complete"))
                .put("wnperf_lines", JSONArray(phases.lines)),
        )
    }

    /** Waits for the visible operation to leave its active phases. */
    private suspend fun awaitTerminal(state: WhiteNoiseAppState): ForwardOperationSnapshot =
        withTimeout(FORWARD_TIMEOUT_MILLIS) {
            var snapshot = state.activeForwardOperation.value
            while (snapshot == null || snapshot.isActive) {
                delay(POLL_MILLIS)
                snapshot = state.activeForwardOperation.value
            }
            snapshot
        }

    /** Waits for the terminal diagnostics line, which the app-scoped owner emits just after the state flips. */
    private suspend fun awaitForwardLines(): List<String> =
        withTimeout(PROJECTION_TIMEOUT_MILLIS) {
            var lines = PerformanceDiagnostics.exportLines().filter { OPERATION in it }
            while (lines.none { " phase=forward_complete " in it }) {
                delay(POLL_MILLIS)
                lines = PerformanceDiagnostics.exportLines().filter { OPERATION in it }
            }
            lines
        }

    /** The one destination reference the forward just published, found in the forwarder's own native history. */
    private suspend fun awaitNewDestinationReference(
        marmot: Marmot,
        peers: ForwardPeers,
        seen: MutableSet<String>,
    ): MediaAttachmentReferenceFfi =
        withTimeout(PROJECTION_TIMEOUT_MILLIS) {
            var fresh: List<MediaAttachmentReferenceFfi> = emptyList()
            while (fresh.isEmpty()) {
                fresh =
                    acceptedReferences(marmot, peers.forwarder.label, peers.destination)
                        .filter { it.ciphertextSha256 !in seen }
                if (fresh.isEmpty()) delay(POLL_MILLIS)
            }
            val reference = fresh.single()
            seen += reference.ciphertextSha256
            reference
        }

    /** Every accepted reference in one account's native attachment history for a group. */
    private suspend fun acceptedReferences(
        marmot: Marmot,
        account: String,
        group: String,
    ): List<MediaAttachmentReferenceFfi> {
        val read = marmot.attachmentHistoryPage(account, group, HISTORY_LIMIT, null)
        if (read !is AttachmentPageReadFfi.Page) return emptyList()
        val page = read.page
        return try {
            page.entries.mapNotNull { (it.attachment as? MediaAttachmentOutcomeFfi.Accepted)?.reference }
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /** Waits until the author's own projection carries the forwarded reference, then returns its request. */
    private suspend fun awaitAuthorProjection(
        marmot: Marmot,
        peers: ForwardPeers,
        reference: MediaAttachmentReferenceFfi,
    ): AttachmentTransferRequest =
        withTimeout(PROJECTION_TIMEOUT_MILLIS) {
            var request: AttachmentTransferRequest? = null
            while (request == null) {
                request = authorRequest(marmot, peers, reference)
                if (request == null) delay(POLL_MILLIS)
            }
            request
        }

    /** One native history read of the author's destination projection for [reference]. */
    private suspend fun authorRequest(
        marmot: Marmot,
        peers: ForwardPeers,
        reference: MediaAttachmentReferenceFfi,
    ): AttachmentTransferRequest? {
        val read = marmot.attachmentHistoryPage(peers.author.label, peers.destination, HISTORY_LIMIT, null)
        if (read !is AttachmentPageReadFfi.Page) return null
        val page = read.page
        return try {
            page.entries.firstNotNullOfOrNull { entry ->
                (entry.attachment as? MediaAttachmentOutcomeFfi.Accepted)
                    ?.takeIf { it.reference.ciphertextSha256 == reference.ciphertextSha256 }
                    ?.let {
                        AttachmentTransferRequest(
                            peers.author.label,
                            peers.destination,
                            entry.messageIdHex,
                            it.attachmentIndex.toInt(),
                            entry.sourceMessageIdHex,
                        )
                    }
            }
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /** Counts the destination's sent media messages so a duplicate publication cannot hide behind a passing forward. */
    private suspend fun reportDestinationTimeline(
        marmot: Marmot,
        peers: ForwardPeers,
    ) {
        val records =
            marmot
                .timelineMessages(
                    peers.forwarder.label,
                    TimelineMessageQueryFfi(
                        groupIdHex = peers.destination,
                        search = null,
                        before = null,
                        beforeMessageId = null,
                        after = null,
                        afterMessageId = null,
                        limit = HISTORY_LIMIT,
                    ),
                ).messages
        val media =
            records.filter { record ->
                record.direction == "sent" && MessageAttachments.acceptedReferences(record.media).isNotEmpty()
            }
        val hashes = media.flatMap { MessageAttachments.acceptedReferences(it.media).map { r -> r.ciphertextSha256 } }
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "forward-destination-timeline")
                .put("sent_media_messages", media.size)
                .put("distinct_ciphertexts", hashes.toSet().size)
                .put("direct", REPETITIONS)
                .put("forwards", REPETITIONS * 3),
        )
    }

    /** Milliseconds between two monotonic readings. */
    private fun millis(
        from: Long,
        to: Long,
    ): Double = (to - from) / NANOS_PER_MILLI
}
