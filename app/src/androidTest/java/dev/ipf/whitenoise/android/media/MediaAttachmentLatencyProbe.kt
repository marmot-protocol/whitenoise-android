package dev.ipf.whitenoise.android.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AppPerformanceSnapshotFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.state.AttachmentDownloadGate
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.ui.conversation.media.decodeMessageAttachmentImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.random.Random

/** Opt-in component timings using generated images and a separate disposable native store. */
@RunWith(AndroidJUnit4::class)
class MediaAttachmentLatencyProbe {
    /** Opt-in controlled received-message probe; public endpoints are never used by this entry point. */
    @Test
    fun measureControlledReceivedAttachment() = runBlocking { ControlledAttachmentProbe.run() }

    /** Compares generated file preparation, upload, and verified cold downloads by payload size. */
    @Test
    fun measureSyntheticSizeMatrix() =
        runBlocking {
            val context = isolatedContext()
            assumeTrue(InstrumentationRegistry.getArguments().getString("allowMediaSizeMatrix") == "true")
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "media-size-probe-${UUID.randomUUID()}").apply { mkdirs() }
            val marmot = Marmot(File(root, "native").absolutePath, MarmotClient.bootstrapRelays)
            try {
                withTimeout(900_000L) {
                    marmot.start()
                    val account = marmot.createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
                    val group = marmot.createGroup(account.label, "Media size measurement", emptyList(), null)
                    val includeNearLimit =
                        InstrumentationRegistry.getArguments().getString("allowNearLimitMediaProbe") == "true"
                    MediaProbeSize.entries
                        .filter { it != MediaProbeSize.NEAR_LIMIT || includeNearLimit }
                        .forEach { size -> measureSize(marmot, account.label, group, size) }
                }
            } finally {
                marmot.shutdownAndClose()
                root.deleteRecursively()
            }
        }

    /** Keeps successful samples and a failure count for one operation. */
    private class OperationSamples {
        val completed = mutableListOf<MediaProbeSample>()
        var failures = 0
    }

    /** Records a successful operation or increments its failure count before rethrowing. */
    private suspend fun <T> recordOperation(
        size: MediaProbeSize,
        samples: OperationSamples,
        block: suspend () -> T,
    ): T =
        try {
            val (result, sample) = measureOperation(size.byteCount.toLong(), block)
            samples.completed += sample
            result
        } catch (failure: Throwable) {
            samples.failures += 1
            throw failure
        }

    /** Reports each operation even if one sample fails, then preserves the original failure. */
    private suspend fun measureSize(
        marmot: Marmot,
        account: String,
        group: String,
        size: MediaProbeSize,
    ) {
        val preparation = OperationSamples()
        val uploads = OperationSamples()
        val downloads = OperationSamples()
        val before = marmot.appPerformanceSnapshot()
        try {
            repeat(size.repetitions) { index ->
                val original = Random(size.ordinal * 10_000 + index).nextBytes(size.byteCount)
                val bytes =
                    recordOperation(size, preparation) {
                        withContext(Dispatchers.IO) {
                            MediaPipeline.readBoundedBytes(ByteArrayInputStream(original), size.byteCount)
                                ?: error("Synthetic source exceeded its known size")
                        }.also { assertArrayEquals(original, it) }
                    }
                val reference =
                    recordOperation(size, uploads) {
                        uploadProbeAttachment(marmot, account, group, index, bytes)
                    }
                recordOperation(size, downloads) {
                    marmot.downloadMedia(account, group, reference).plaintext.also {
                        assertArrayEquals(bytes, it)
                    }
                }
            }
        } finally {
            reportAggregate(MediaProbeOperation.PREPARATION, size, preparation.completed, preparation.failures)
            reportAggregate(MediaProbeOperation.UPLOAD, size, uploads.completed, uploads.failures)
            reportAggregate(MediaProbeOperation.DOWNLOAD, size, downloads.completed, downloads.failures)
            reportNativeInterval(before, marmot.appPerformanceSnapshot(), size)
        }
    }

    /** Uploads one generated payload without posting a message to the probe group. */
    private suspend fun uploadProbeAttachment(
        marmot: Marmot,
        account: String,
        group: String,
        index: Int,
        bytes: ByteArray,
    ): MediaAttachmentReferenceFfi =
        marmot
            .uploadMedia(
                account,
                group,
                MediaUploadRequestFfi(
                    attachments =
                        listOf(
                            MediaUploadAttachmentRequestFfi(
                                "sample-$index.bin",
                                "application/octet-stream",
                                bytes,
                                null,
                                null,
                            ),
                        ),
                    caption = null,
                    send = false,
                    blossomServer = null,
                ),
            ).attachments
            .single()
            .reference

    /** Samples both heaps during an operation; absolute peaks are diagnostic, not allocation deltas. */
    private suspend fun <T> measureOperation(
        payloadBytes: Long,
        block: suspend () -> T,
    ): Pair<T, MediaProbeSample> =
        coroutineScope {
            val peakJava = AtomicLong()
            val peakNative = AtomicLong()

            fun sampleMemory() {
                val runtime = Runtime.getRuntime()
                peakJava.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                peakNative.updateAndGet { maxOf(it, Debug.getNativeHeapAllocatedSize()) }
            }
            sampleMemory()
            val sampler =
                launch(Dispatchers.Default) {
                    while (isActive) {
                        sampleMemory()
                        delay(50L)
                    }
                }
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                val value = block()
                sampleMemory()
                value to MediaProbeSample(elapsedMs(started), payloadBytes, peakJava.get(), peakNative.get())
            } finally {
                sampler.cancelAndJoin()
            }
        }

    /** Exports one privacy-bounded JSON record per operation and size. */
    private fun reportAggregate(
        operation: MediaProbeOperation,
        size: MediaProbeSize,
        successes: List<MediaProbeSample>,
        failures: Int,
    ) {
        val line = mediaProbeAggregateJson(operation, size, successes, failures)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("media_probe_json", line) },
        )
    }

    /** Measures live native downloads and retains aggregate native phase evidence on failure. */
    @Test
    fun measureSyntheticImagePhases() =
        runBlocking {
            val context = isolatedContext()
            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "media-probe-${UUID.randomUUID()}").apply { mkdirs() }
            val marmot = Marmot(File(root, "native").absolutePath, MarmotClient.bootstrapRelays)
            try {
                withTimeout(600_000L) {
                    marmot.start()
                    val account = marmot.createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
                    val group = marmot.createGroup(account.label, "Media measurement", emptyList(), null)
                    val images = List(16) { syntheticImage(seed = 42 + it) }
                    val attachments =
                        images.mapIndexed { index, bytes ->
                            MediaUploadAttachmentRequestFfi("sample-$index.png", "image/png", bytes, "128x128", null)
                        }
                    report("fixture_bytes", images.map { it.size.toDouble() })
                    val references =
                        marmot
                            .uploadMedia(
                                account.label,
                                group,
                                MediaUploadRequestFfi(
                                    attachments = attachments,
                                    caption = null,
                                    send = false,
                                    blossomServer = null,
                                ),
                            ).attachments
                            .map { it.reference }
                    assertEquals(16, references.size)
                    assertEquals(16, references.map { it.ciphertextSha256 }.distinct().size)
                    val before = marmot.appPerformanceSnapshot()
                    try {
                        measureNativePhases(marmot, account.label, group, references.zip(images))
                    } finally {
                        reportNativeInterval(before, marmot.appPerformanceSnapshot())
                    }
                }
            } finally {
                marmot.shutdownAndClose()
                root.deleteRecursively()
            }
        }

    /** Network failures cannot suppress independent encrypted-cache and platform-decode evidence. */
    @Test
    fun measureLocalImagePhasesWithoutNetwork() =
        runBlocking {
            val context = isolatedContext()
            val root = File(context.cacheDir, "media-probe-${UUID.randomUUID()}").apply { mkdirs() }
            val keyAlias = "media.probe.${UUID.randomUUID()}"
            try {
                withTimeout(60_000L) { measureLocalPhases(root, keyAlias, syntheticImage(seed = 42)) }
            } finally {
                root.deleteRecursively()
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            }
        }

    /** Verifies the real admission path returns verified memory and encrypted-disk hits without fetching. */
    @Test
    fun admissionReusesPlatformCaches() =
        runBlocking {
            val context = isolatedContext()
            withContext(Dispatchers.Main.immediate) {
                val state =
                    WhiteNoiseAppState(
                        context = context,
                        draftStore = DraftStore(DiscardedDrafts),
                        accountIdHexResolver = { null },
                        accounts = emptyList(),
                        activeAccountRef = "probe-account",
                    )
                val bytes = syntheticImage(seed = 42)
                measureMemoryAdmission(state, bytes)
                measureDiskAdmission(state, bytes)
            }
        }

    /** Times twenty actual warm-memory admissions and forbids invoking the native producer. */
    private suspend fun measureMemoryAdmission(
        state: WhiteNoiseAppState,
        bytes: ByteArray,
    ) {
        val request = AttachmentTransferRequest("probe-account", "probe-group", UUID.randomUUID().toString(), 0)
        state.cacheMediaPlaintext(request.cacheKey(), bytes)
        val samples =
            List(20) {
                val started = SystemClock.elapsedRealtimeNanos()
                val result =
                    state
                        .memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Interactive) {
                            error("Memory hit must not fetch")
                        }.await()
                assertSame(bytes, result)
                elapsedMs(started)
            }
        report("memory_cache_admission_ms", samples)
    }

    /** Uses a new key per sample so each admission is a real encrypted-disk hit, never a prior L1 hit. */
    private suspend fun measureDiskAdmission(
        state: WhiteNoiseAppState,
        bytes: ByteArray,
    ) {
        val samples =
            List(20) {
                val request = AttachmentTransferRequest("probe-account", "probe-group", UUID.randomUUID().toString(), 0)
                try {
                    withContext(Dispatchers.IO) {
                        val token = state.diskMediaCache.capturePublicationToken()
                        state.diskMediaCache.put(request.cacheKey(), bytes, token)
                    }
                    val started = SystemClock.elapsedRealtimeNanos()
                    val result =
                        state
                            .memoizedDownload(request.cacheKey(), request, AttachmentDownloadPriority.Automatic) {
                                error("Encrypted disk hit must not fetch")
                            }.await()
                    assertArrayEquals(bytes, result)
                    assertSame(result, state.cachedMediaPlaintext(request.cacheKey()))
                    elapsedMs(started)
                } finally {
                    withContext(Dispatchers.IO) { state.diskMediaCache.remove(request.cacheKey()) }
                }
            }
        report("encrypted_cache_admission_ms", samples)
    }

    /** Rejects implicit execution and every package that could hold personal app state. */
    private fun isolatedContext(): Context {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowMediaProbe") == "true")
        return InstrumentationRegistry.getInstrumentation().targetContext.also { context ->
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency") {
                "Requires the isolated measurement package"
            }
        }
    }

    /** Bypasses Android caches and measures distinct references through the shipped native client. */
    private suspend fun measureNativePhases(
        marmot: Marmot,
        account: String,
        group: String,
        images: List<Pair<MediaAttachmentReferenceFfi, ByteArray>>,
    ) {
        val (reference, bytes) = images.first()
        val downloads = mutableListOf<Double>()
        repeat(20) {
            val started = SystemClock.elapsedRealtimeNanos()
            val result = marmot.downloadMedia(account, group, reference)
            downloads += elapsedMs(started)
            assertArrayEquals(bytes, result.plaintext)
        }
        report("native_download_ms", downloads)
        val requests = mutableListOf<Double>()
        val batches = mutableListOf<Double>()
        repeat(20) {
            val started = SystemClock.elapsedRealtimeNanos()
            requests += measureDistinctBacklog(marmot, account, group, images)
            batches += elapsedMs(started)
        }
        report("native_distinct_backlog_request_ms", requests)
        report("native_distinct_backlog_batch_ms", batches)
    }

    /** One cold sixteen-image batch; the host cap is asserted separately from unobservable native HTTP work. */
    private suspend fun measureDistinctBacklog(
        marmot: Marmot,
        account: String,
        group: String,
        images: List<Pair<MediaAttachmentReferenceFfi, ByteArray>>,
    ): List<Double> =
        coroutineScope {
            val gate = AttachmentDownloadGate()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            images
                .map { (reference, bytes) ->
                    async(Dispatchers.Default) {
                        val started = SystemClock.elapsedRealtimeNanos()
                        gate.withPermit {
                            val now = active.incrementAndGet()
                            peak.updateAndGet { maxOf(it, now) }
                            try {
                                assertArrayEquals(bytes, marmot.downloadMedia(account, group, reference).plaintext)
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                        elapsedMs(started)
                    }
                }.awaitAll()
                .also {
                    assertTrue("host attachment concurrency must remain bounded", peak.get() in 1..3)
                    assertEquals(0, active.get())
                }
        }

    /** Reads only the existing closed aggregate media schema; never enables remote telemetry. */
    private fun reportNativeInterval(
        before: AppPerformanceSnapshotFfi,
        after: AppPerformanceSnapshotFfi,
        size: MediaProbeSize? = null,
    ) {
        val previous = before.mediaPhases()
        after.mediaPhases().forEach { (phase, operation) ->
            mediaProbePhaseReport(phase, previous.getValue(phase), operation).forEach { line ->
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString("media_probe_native", size?.let { "size=${it.wireName} $line" } ?: line)
                    },
                )
            }
        }
    }

    /** Explicit mapping prevents new unrelated snapshot fields or free-form labels from being exported. */
    private fun AppPerformanceSnapshotFfi.mediaPhases() =
        mapOf(
            MediaProbeNativePhase.UPLOAD to mediaUpload,
            MediaProbeNativePhase.DOWNLOAD to mediaDownload,
            MediaProbeNativePhase.QUEUE_WAIT to mediaDownloadQueueWait,
            MediaProbeNativePhase.PREPARATION to mediaDownloadPreparation,
            MediaProbeNativePhase.HOST_SETUP to mediaDownloadHostSetup,
            MediaProbeNativePhase.RESPONSE_HEADERS to mediaDownloadResponseHeaders,
            MediaProbeNativePhase.FIRST_BYTE to mediaDownloadFirstByte,
            MediaProbeNativePhase.BODY_TRANSFER to mediaDownloadBodyTransfer,
            MediaProbeNativePhase.LOCATOR_FAILOVER to mediaDownloadLocatorFailover,
            MediaProbeNativePhase.CIPHERTEXT_VERIFY to mediaDownloadCiphertextVerify,
            MediaProbeNativePhase.DECRYPT to mediaDownloadDecrypt,
            MediaProbeNativePhase.PLAINTEXT_VERIFY to mediaDownloadPlaintextVerify,
        )

    /** Measures only test-owned encrypted entries and real platform image decoding. */
    private suspend fun measureLocalPhases(
        root: File,
        keyAlias: String,
        bytes: ByteArray,
    ) {
        val cache =
            DiskByteCache(
                cacheDir = File(root, "cache"),
                maxBytes = 8L * 1024L * 1024L,
                keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(keyAlias),
            )
        val writes = mutableListOf<Double>()
        val reads = mutableListOf<Double>()
        val decodes = mutableListOf<Double>()
        repeat(20) { index ->
            val key = "sample-$index"
            var started = SystemClock.elapsedRealtimeNanos()
            withContext(Dispatchers.IO) { cache.put(key, bytes, cache.capturePublicationToken()) }
            writes += elapsedMs(started)
            started = SystemClock.elapsedRealtimeNanos()
            val cached = withContext(Dispatchers.IO) { cache.get(key) }
            reads += elapsedMs(started)
            assertArrayEquals(bytes, cached)
            started = SystemClock.elapsedRealtimeNanos()
            assertNotNull(decodeMessageAttachmentImage(bytes, "image/png", MediaPipeline.THUMBNAIL_MAX_EDGE_PX))
            decodes += elapsedMs(started)
        }
        report("encrypted_cache_write_ms", writes)
        report("encrypted_cache_read_ms", reads)
        report("image_decode_ms", decodes)
    }

    /** Generates approximately 64 KiB of valid image data without reading user media. */
    private fun syntheticImage(seed: Int): ByteArray {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(IntArray(128 * 128) { random.nextInt() }, 0, 128, 0, 0, 128, 128)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Returns monotonic component duration in milliseconds. */
    private fun elapsedMs(started: Long): Double = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0

    /** Exports only fixed phase names and aggregate measurements to instrumentation output. */
    private fun report(
        phase: String,
        samples: List<Double>,
    ) {
        val sorted = samples.sorted()
        val result =
            "phase=$phase count=${sorted.size} p50=${sorted[ceil(sorted.size * 0.5).toInt() - 1]} " +
                "p95=${sorted[ceil(sorted.size * 0.95).toInt() - 1]} max=${sorted.last()}"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("media_probe", result) })
    }

    /** Does not persist synthetic fixture drafts or call the native runtime. */
    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
