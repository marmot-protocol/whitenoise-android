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
import dev.ipf.whitenoise.android.media.RestartAttachmentRetentionProbe
import dev.ipf.whitenoise.android.media.SenderHarness
import dev.ipf.whitenoise.android.media.awaitAndroidFixtureReferences
import dev.ipf.whitenoise.android.media.openSenderHarness
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.FileUploadProgress
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.io.InputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

private const val MIB = 1024L * 1024L
private const val CANCEL_BYTES = 256L * MIB
private const val RETRY_BYTES = 64L * MIB
private const val TIMEOUT_MILLIS = 20L * 60L * 1000L

/**
 * Qualifies the large-send path of #2284 on a disposable emulator against the loopback Blossom fixture and
 * relay, through the shipping controller and MDK's file upload. Every fact reported is closed: sizes,
 * booleans, phases and times, never names, keys or identifiers. The host checker judges the run, including
 * the fixture's own upload ledger.
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
                session.close(preserve = false)
            }
        }

    /** Sends one file at the ceiling and reports heap, progress, exactness and cleanup. */
    private suspend fun largeSend(
        session: FixtureSession,
        peers: FixturePeers,
        harness: SenderHarness,
        bytes: Long,
    ) {
        val staged = stage(session, bytes, "large.bin")
        val baseline = heapNow()
        val sampler = HeapSampler()
        val started = SystemClock.elapsedRealtime()
        val queued = queue(harness.controller, staged.attachment)
        val progress = ProgressRecorder(harness.controller, queued.optimistic.messageIdHex)
        withContext(Dispatchers.Main.immediate) { harness.controller.uploadQueued(queued) }
        val sent = awaitAndroidFixtureReferences(session.marmot, peers.sender.label, peers.group, listOf("large.bin"))
        val elapsed = SystemClock.elapsedRealtime() - started
        val peak = sampler.stop()
        val samples = progress.stop()
        report(
            JSONObject()
                .put("scenario", "large-send")
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

    /** Cancels a large send while MDK is working on it and reports that nothing was published. */
    private suspend fun cancelledSend(
        session: FixtureSession,
        peers: FixturePeers,
        harness: SenderHarness,
    ) {
        val staged = stage(session, CANCEL_BYTES, "cancelled.bin")
        val queued = queue(harness.controller, staged.attachment)
        val progress = ProgressRecorder(harness.controller, queued.optimistic.messageIdHex)
        val upload =
            CoroutineScope(Dispatchers.Main.immediate).async {
                runCatching { harness.controller.uploadQueued(queued) }
            }
        // Cancel only once MDK has copied part of the file, so the stop reaches a running transfer.
        val seenWorking =
            withTimeout(60_000L) {
                harness.controller
                    .pendingUploadProgress(queued.optimistic.messageIdHex)
                    ?.first { it != null && it.fraction > 0.05f }
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
        val staged = stage(session, RETRY_BYTES, "retried.bin")
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
                .put("bytes", RETRY_BYTES)
                .put("first_attempt_unpublished", failedUnpublished)
                .put("kept_for_retry", keptForRetry)
                .put("sha256_matches", sent.references.single().plaintextSha256 == staged.sha256)
                .put("snapshot_deleted", awaitDeleted(staged.file)),
        )
    }

    /** One generated file staged the way the composer stages a large pick, with its SHA-256. */
    private class Staged(
        val attachment: PendingAttachment,
        val file: File,
        val sha256: String,
    )

    /** Stages [bytes] of generated content into this process's private upload directory as [name]. */
    private suspend fun stage(
        session: FixtureSession,
        bytes: Long,
        name: String,
    ): Staged =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            val directory = uploadSourcesDirectory(session.context.cacheDir)
            val read =
                readStagedDocument(directory, FILE_BACKED_ATTACHMENT_MAX_BYTES) { GeneratedStream(bytes, digest) }
            val source = (read as StagedDocumentRead.Success).source
            check(source.byteCount == bytes)
            Staged(
                PendingAttachment(ByteArray(0), "application/octet-stream", name, sourceFile = source),
                source.file,
                digest.digest().joinToString("") { "%02x".format(it) },
            )
        }

    /** Queues [attachment] as one optimistic send, the way the composer does. */
    private suspend fun queue(
        controller: ConversationController,
        attachment: PendingAttachment,
    ): ConversationController.QueuedAttachmentSend =
        withContext(Dispatchers.Main.immediate) {
            requireNotNull(controller.queueAttachments(listOf(attachment), caption = null)) { "send not admitted" }
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

    /** The file names of every attachment the sender's group has published so far. */
    private suspend fun publishedFileNames(
        session: FixtureSession,
        peers: FixturePeers,
    ): Set<String> {
        val read = session.marmot.attachmentHistoryPage(peers.sender.label, peers.group, 100u, null)
        if (read !is AttachmentPageReadFfi.Page) return emptySet()
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

    /** Records every progress value the controller publishes for one pending send. */
    private class ProgressRecorder(
        controller: ConversationController,
        messageIdHex: String,
    ) {
        private val samples = CopyOnWriteArrayList<FileUploadProgress>()
        private val job =
            CoroutineScope(Dispatchers.Default).launch {
                controller.pendingUploadProgress(messageIdHex)?.collect { value -> value?.let(samples::add) }
            }

        /** Stops recording and returns the values seen, in order. */
        suspend fun stop(): List<FileUploadProgress> {
            job.cancel()
            job.join()
            return samples.toList()
        }
    }

    /** Deterministic pseudo-random bytes of a fixed length, hashed as they are read. */
    private class GeneratedStream(
        private val length: Long,
        private val digest: MessageDigest,
    ) : InputStream() {
        private var produced = 0L
        private var state = -0x61c8864680b583ebL

        /** Produces one byte. */
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        /** Fills [buffer] with the next bytes of the sequence. */
        override fun read(
            buffer: ByteArray,
            offset: Int,
            count: Int,
        ): Int {
            if (produced >= length) return -1
            val n = minOf(count.toLong(), length - produced).toInt()
            for (index in 0 until n) {
                state = state xor (state shl 13)
                state = state xor (state ushr 7)
                state = state xor (state shl 17)
                buffer[offset + index] = state.toByte()
            }
            digest.update(buffer, offset, n)
            produced += n
            return n
        }
    }
}
