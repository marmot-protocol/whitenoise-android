package dev.ipf.whitenoise.android.ui.conversation.media

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.media.FixturePeers
import dev.ipf.whitenoise.android.media.FixtureSession
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.RestartAttachmentRetentionProbe
import dev.ipf.whitenoise.android.media.SenderHarness
import dev.ipf.whitenoise.android.media.awaitAndroidFixtureReferences
import dev.ipf.whitenoise.android.media.openSenderHarness
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.FileUploadPhase
import dev.ipf.whitenoise.android.state.FileUploadProgress
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.conversation.ConversationAttachmentReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

private const val MIB = 1024L * 1024L
private const val CANCEL_BYTES = 256L * MIB
private const val RETRY_BYTES = 64L * MIB
private const val TIMEOUT_MILLIS = 20L * 60L * 1000L

/**
 * Qualifies the large-send path of #2284 on a disposable emulator, or on one explicitly authorized physical
 * device, against the loopback Blossom fixture and relay, through the shipping controller and MDK's file
 * upload. Every file is a generated `content://` document picked through the composer's own document reader.
 * Every fact reported is closed: sizes, booleans, phases and times, never names, keys or identifiers. The
 * host checker judges the run, including the fixture's own upload ledger.
 *
 * - A file at the per-file ceiling (512 MiB less the 16-byte tag, or `fixtureLargeSendBytes`) sends with
 *   bounded heap, exact plaintext, monotonic progress through every step, and its staged copy deleted.
 * - A large send cancelled while MDK works on it publishes nothing and deletes its staged copy.
 * - A send whose media server is unreachable fails, keeps its staged copy, and Retry publishes it.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class FileBackedLargeSendDeviceTest {
    /** Runs the three scenarios in one generated session and reports each one's facts. */
    @Test
    fun largeSendStaysBoundedAndRecoversThroughCancelAndRetry() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
            val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
            val relays = listOf("ws://127.0.0.1:${requireNotNull(arguments.getString("fixtureRelayPort")).toInt()}")
            val largeBytes = arguments.getString("fixtureLargeSendBytes")?.toLong() ?: FILE_BACKED_ATTACHMENT_MAX_BYTES
            require(largeBytes in (CANCEL_BYTES + 1)..FILE_BACKED_ATTACHMENT_MAX_BYTES)
            MarmotAndroid.initialize(context)
            val root = RestartAttachmentRetentionProbe.createRoot(context, null, null)
            val marmot = TileFixtureSupport.openRuntime(root, relays)
            val session = FixtureSession(context, root, marmot, relays, blobPort)
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    marmot.start()
                    val peers = session.createPeers()
                    val harness =
                        openSenderHarness(
                            context,
                            root,
                            marmot,
                            peers.sender,
                            peers.group,
                            blobPort,
                            holdHostCopy = false,
                        )
                    try {
                        withContext(Dispatchers.Main.immediate) { harness.controller.retryMembers() }
                        largeSend(session, peers, harness, largeBytes)
                        cancelledSend(session, peers, harness)
                        retriedSend(session, peers, harness, blobPort)
                    } finally {
                        harness.close()
                    }
                }
            } finally {
                try {
                    session.close(preserve = false)
                } finally {
                    // Nothing in this isolated package sweeps its upload directory, so a run that stopped before
                    // a send released its staged copy, or whose session failed to close, must not leave hundreds
                    // of MiB behind on the device.
                    File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES).deleteRecursively()
                }
            }
        }

    /** Sends one file at the ceiling and reports heap, progress, exactness and cleanup. */
    private suspend fun largeSend(
        session: FixtureSession,
        peers: FixturePeers,
        harness: SenderHarness,
        bytes: Long,
    ) {
        val staged = stage(session, harness, bytes, "large.bin")
        val baseline = heapNow()
        val sampler = HeapSampler()
        val started = SystemClock.elapsedRealtime()
        val queued = queue(harness.controller, staged.attachment)
        val progress = ProgressRecorder(progressOf(harness.controller, queued))
        withContext(Dispatchers.Main.immediate) { harness.controller.uploadQueued(queued) }
        val sent = awaitAndroidFixtureReferences(session.marmot, peers.sender.label, peers.group, listOf("large.bin"))
        val elapsed = SystemClock.elapsedRealtime() - started
        val peak = sampler.stop()
        val samples = progress.stop()
        report(
            JSONObject()
                .put("scenario", "large-send")
                .put("picked_from_content_uri", staged.pickedFromContentUri)
                .put("bytes", bytes)
                .put("ms", elapsed)
                .put("sha256_matches", sent.references.single().plaintextSha256 == staged.sha256)
                .put("java_baseline_bytes", baseline.java)
                .put("java_peak_bytes", peak.java)
                .put("native_baseline_bytes", baseline.native)
                .put("native_peak_bytes", peak.native)
                .put("phases", JSONArray(samples.map { it.phase.name }.distinct()))
                .put("monotonic", samples.zipWithNext().all { (earlier, later) -> later.fraction >= earlier.fraction })
                .put("final_phase", samples.lastOrNull()?.phase?.name ?: JSONObject.NULL)
                .put("snapshot_deleted", awaitDeleted(staged.file)),
        )
    }

    /**
     * Cancels a large send while MDK is uploading it and reports that nothing was published. The upload runs
     * as a child of this scenario, so a timeout or teardown cancels it rather than leaving it running.
     */
    private suspend fun cancelledSend(
        session: FixtureSession,
        peers: FixturePeers,
        harness: SenderHarness,
    ) = coroutineScope {
        val staged = stage(session, harness, CANCEL_BYTES, "cancelled.bin")
        val queued = queue(harness.controller, staged.attachment)
        val progressFlow = progressOf(harness.controller, queued)
        val progress = ProgressRecorder(progressFlow)
        val upload =
            async(Dispatchers.Main.immediate) {
                runCatching { harness.controller.uploadQueued(queued) }
            }
        // Cancel only once bytes are on the wire, so the stop interrupts a running upload rather than the copy.
        val seenWorking =
            withTimeout(120_000L) {
                progressFlow?.first { it != null && it.phase == FileUploadPhase.UPLOADING && it.phaseBytes > 0L }
            }
        val cancelled =
            withContext(Dispatchers.Main.immediate) {
                harness.controller.deleteMessage(queued.optimistic, presentFailure = false)
            }
        upload.await()
        progress.stop()
        // A cancelled send must never reach the group, so wait past the time a publish would take.
        delay(5_000L)
        report(
            JSONObject()
                .put("scenario", "cancel")
                .put("picked_from_content_uri", staged.pickedFromContentUri)
                .put("bytes", CANCEL_BYTES)
                .put("cancelled_while_working", seenWorking != null && cancelled)
                .put("published", "cancelled.bin" in publishedFileNames(session, peers))
                .put("snapshot_deleted", awaitDeleted(staged.file)),
        )
    }

    /** Fails a send against an unreachable media server, then retries it from the same staged copy. */
    private suspend fun retriedSend(
        session: FixtureSession,
        peers: FixturePeers,
        harness: SenderHarness,
        blobPort: Int,
    ) {
        val staged = stage(session, harness, RETRY_BYTES, "retried.bin")
        val closedPort = ServerSocket(0).use { it.localPort }
        setEndpoint(session, peers, closedPort)
        val queued = queue(harness.controller, staged.attachment)
        withContext(Dispatchers.Main.immediate) { harness.controller.uploadQueued(queued) }
        val failedUnpublished = "retried.bin" !in publishedFileNames(session, peers)
        val keptForRetry = staged.file.exists()
        setEndpoint(session, peers, blobPort)
        val failed =
            TimelineMessage(queued.key, queued.optimistic, MessageStatus.Failed, timelineOrder = queued.optimisticOrder)
        withContext(Dispatchers.Main.immediate) { harness.controller.retryFailedSend(failed) }
        val sent = awaitAndroidFixtureReferences(session.marmot, peers.sender.label, peers.group, listOf("retried.bin"))
        report(
            JSONObject()
                .put("scenario", "retry")
                .put("picked_from_content_uri", staged.pickedFromContentUri)
                .put("bytes", RETRY_BYTES)
                .put("first_attempt_unpublished", failedUnpublished)
                .put("kept_for_retry", keptForRetry)
                .put("sha256_matches", sent.references.single().plaintextSha256 == staged.sha256)
                .put("snapshot_deleted", awaitDeleted(staged.file)),
        )
    }

    /**
     * One generated document picked and staged the way the composer stages a large pick, with its SHA-256.
     * [pickedFromContentUri] marks a report from this version of the fixture, so the host checker rejects an
     * older test APK that staged directly. The provider path itself is witnessed by the size check and the
     * SHA-256 match, not by this flag.
     */
    private class Staged(
        val attachment: PendingAttachment,
        val file: File,
        val sha256: String,
        val pickedFromContentUri: Boolean,
    )

    /**
     * Picks [bytes] of generated content as the `content://` document [name] through the composer's own
     * document reader, which stages it into this process's private upload directory. The expected SHA-256 is
     * computed from the generator, apart from the bytes the reader staged. A pick that fails a check releases
     * whatever the reader staged before the failure is thrown.
     */
    private suspend fun stage(
        session: FixtureSession,
        harness: SenderHarness,
        bytes: Long,
        name: String,
    ): Staged =
        withContext(Dispatchers.IO) {
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            val uri = GeneratedDocumentProvider.uri(testPackage, bytes, name)
            val read =
                ConversationAttachmentReader(harness.state, session.context)
                    .readPickedDocuments(listOf(uri), allowFileBacked = true)
            var kept = false
            try {
                check(read.failures.isEmpty()) { "generated pick was refused: ${read.failures}" }
                val attachment = read.attachments.single()
                val source = requireNotNull(attachment.sourceFile) { "generated pick was not staged to a file" }
                check(source.byteCount == bytes) { "staged ${source.byteCount} of $bytes bytes" }
                check(attachment.fileName == name) { "the staged pick lost its display name" }
                val sha256 = GeneratedDocumentProvider.sha256(bytes)
                Staged(attachment, source.file, sha256, uri.scheme == "content").also { kept = true }
            } finally {
                if (!kept) read.attachments.forEach { it.sourceFile?.closeQuietly() }
            }
        }

    /**
     * Queues [attachment] as one optimistic send, the way the composer does. A send the controller does not
     * admit releases its staged copy, as the composer does for an unadopted pick.
     */
    private suspend fun queue(
        controller: ConversationController,
        attachment: PendingAttachment,
    ): ConversationController.QueuedAttachmentSend =
        withContext(Dispatchers.Main.immediate) {
            val queued = controller.queueAttachments(listOf(attachment), caption = null)
            if (queued == null) attachment.sourceFile?.closeQuietly()
            requireNotNull(queued) { "send not admitted" }
        }

    /** Points the generated group at a media server on [port]. */
    private suspend fun setEndpoint(
        session: FixtureSession,
        peers: FixturePeers,
        port: Int,
    ) {
        val endpoint = AppBlobEndpointFfi("blossom-v1", "http://127.0.0.1:$port")
        session.marmot.replaceEncryptedMediaBlobEndpoints(peers.sender.label, peers.group, listOf(endpoint))
    }

    /**
     * The file names of every attachment the sender's group has published so far. An empty set means a page
     * was read and held nothing: a history that cannot be read fails the fixture rather than passing as
     * "nothing published".
     */
    private suspend fun publishedFileNames(
        session: FixtureSession,
        peers: FixturePeers,
    ): Set<String> {
        val read =
            withTimeout(30_000L) {
                var read = session.marmot.attachmentHistoryPage(peers.sender.label, peers.group, 100u, null)
                while (read !is AttachmentPageReadFfi.Page) {
                    delay(250L)
                    read = session.marmot.attachmentHistoryPage(peers.sender.label, peers.group, 100u, null)
                }
                read
            }
        val page = read.page
        return try {
            page.entries
                .mapNotNull { (it.attachment as? MediaAttachmentOutcomeFfi.Accepted)?.reference?.fileName }
                .toSet()
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /** Waits for the controller's off-main release to delete [file]. */
    private suspend fun awaitDeleted(file: File): Boolean {
        repeat(100) {
            if (!file.exists()) return true
            delay(100L)
        }
        return !file.exists()
    }

    /** Reports one scenario's closed facts to the host runner. */
    private fun report(facts: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("controlled_attachment_json", facts.toString()) },
        )
    }

    /** Java heap in use and native heap allocated at one moment. */
    private data class Heap(
        val java: Long,
        val native: Long,
    )

    /** Reads the current heap use. */
    private fun heapNow(): Heap {
        val runtime = Runtime.getRuntime()
        return Heap(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize())
    }

    /** Samples heap every 50 ms on a background thread and keeps each peak until [stop]. */
    private inner class HeapSampler {
        @Volatile private var javaPeak = 0L

        @Volatile private var nativePeak = 0L
        private val job =
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    val now = heapNow()
                    if (now.java > javaPeak) javaPeak = now.java
                    if (now.native > nativePeak) nativePeak = now.native
                    delay(50L)
                }
            }

        /** Stops sampling and returns the peaks seen. */
        suspend fun stop(): Heap {
            job.cancel()
            job.join()
            return Heap(javaPeak, nativePeak)
        }
    }

    /**
     * The controller's progress for [queued]. The retained uploads live in an access-ordered cache that
     * only Main touches, so the lookup runs there, and the flow itself may be collected anywhere.
     */
    private suspend fun progressOf(
        controller: ConversationController,
        queued: ConversationController.QueuedAttachmentSend,
    ): StateFlow<FileUploadProgress?>? {
        val messageIdHex = queued.optimistic.messageIdHex
        return withContext(Dispatchers.Main.immediate) { controller.pendingUploadProgress(messageIdHex) }
    }

    /**
     * Records every progress value shown for one pending send. The shown values never move back by design,
     * so the recorded order is what a person watching the bubble saw.
     */
    private class ProgressRecorder(
        progress: StateFlow<FileUploadProgress?>?,
    ) {
        private val samples = CopyOnWriteArrayList<FileUploadProgress>()
        private val job =
            CoroutineScope(Dispatchers.Default).launch {
                progress?.collect { value -> value?.let(samples::add) }
            }

        /** Stops recording and returns the values seen, in order. */
        suspend fun stop(): List<FileUploadProgress> {
            job.cancel()
            job.join()
            return samples.toList()
        }
    }
}
