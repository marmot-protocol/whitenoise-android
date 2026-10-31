package dev.ipf.whitenoise.android.media.sandbox

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Unpaced production-route measurements on unchanged tiny inputs; no camera/HDR or exact-peak qualification. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class MediaAvPerformanceDeviceTest {
    @Test(timeout = 600_000) // One finite 88-observation campaign; other device cases keep the runner default.
    fun measureProductionCostsOnUnchangedCorpus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = mutableListOf<PerformanceInput>()
        val heldSources = mutableSetOf<File>()
        val canary = File(context.filesDir, "media-containment-canary")
        check(canary.createNewFile()) { "Another fixture owns the private canary" }
        var originalFailure: Throwable? = null
        try {
            canary.writeText("synthetic-production-performance-canary")
            IsolatedMediaDecoder.install(context)
            // Copy and verify every unchanged input before any measured interval.
            stageInputs(staged)
            ProductionPerformanceReport().use { report ->
                val campaign = ProductionPerformanceCampaign(report, heldSources)
                for (input in staged) {
                    repeat(3) { iteration ->
                        campaign.automatic(input, iteration, false)
                        if (!input.video || input.name == "original-video.mp4") {
                            campaign.automatic(input, iteration, true)
                        }
                        campaign.stream(input, iteration, false)
                    }
                    campaign.stream(input, 0, true) // Sampling overhead is excluded from all throughput rows.
                }
                report.complete(88, campaign.failures.size)
                check(campaign.failures.isEmpty()) { campaign.failures.joinToString("\n") }
            }
        } catch (failure: Throwable) {
            originalFailure = failure
        }
        val cleanupFailure = cleanFixtures(staged, heldSources, canary)
        originalFailure?.let { failure ->
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
        cleanupFailure?.let { throw it }
    }

    private fun cleanFixtures(
        staged: List<PerformanceInput>,
        heldSources: Set<File>,
        canary: File,
    ): Throwable? =
        runCatching {
            val deleted = staged.map { it.file !in heldSources && it.file.delete() }.all { it }
            if (!canary.delete() || !deleted) {
                val retained = heldSources.map { it.name }
                val message = "Performance fixture cleanup incomplete; retained sources: $retained"
                throw IllegalStateException(message)
            }
        }.exceptionOrNull()

    private fun stageInputs(staged: MutableList<PerformanceInput>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val native =
            linkedMapOf(
                "red-h264.mp4" to "e38bdb7f73666af67210e32943d0a8310b022cbe98a8dc07773cf43ec73c42ee",
                "red-vp9.webm" to "a307d6f4488575ce6c6d7ea5bd7f9f685bbf53ef1e9f99fc4f46d56cbf4829d3",
                "red-av1.webm" to "bd2653808db552bc8f8138558aa333afc62bb89e3c2a3b8c5591e5186aede17d",
                "red-hevc.mp4" to "78a717a6eda4f149f38332017b08c171906b7845b2706b40de1cf8f9d4c7f0c6",
                "tone-aac.m4a" to "45890dbde9712c4184269c43aa9105ed9d76ebaff3560a08b0476eee281c578a",
                "tone-opus.ogg" to "b90d8412844f19e71282d5e5141f7ad6c6e82dd6205f508f7b9e6cf3e24d3e39",
                "tone-mp3.mp3" to "cecb674506ecb6f643df47ee841a6f74313d34940286691490bee780af460872",
                "tone-pcm.wav" to "4b401c22c51b7ecf5381aaf44af0d645f20dbf7d0ae0092d228172bd5d669b1d",
            )
        for ((name, digest) in native) {
            val bytes =
                instrumentation.context.assets
                    .open("native-av-corpus/$name")
                    .use { it.readBytes() }
            stage(staged, name, bytes, digest, name.startsWith("red-"))
        }
        stage(
            staged,
            "original-video.mp4",
            verifiedVideoFixture(),
            "83fbcd994ece32535285a0ea6505c681cb96c471736308316eabda90cced9f51",
            true,
        )
        stage(
            staged,
            "original-wave.wav",
            pcmWaveFixture(),
            "8621ab2fb3a76d3eb5be180f9c000cae911fc67b9e4f6ab27f9aaaca0c38505a",
            false,
        )
        check(staged.size == 10)
    }

    private fun stage(
        staged: MutableList<PerformanceInput>,
        name: String,
        bytes: ByteArray,
        digest: String,
        video: Boolean,
    ) {
        check(performanceSha(bytes) == digest) { "Changed performance fixture: $name" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("av-performance-", ".${name.substringAfterLast('.')}", context.cacheDir)
        staged += PerformanceInput(name, file, digest, bytes.size.toLong(), video)
        file.writeBytes(bytes)
        check(file.length() == bytes.size.toLong() && performanceSha(file.readBytes()) == digest)
    }
}

private data class PerformanceInput(
    val name: String,
    val file: File,
    val sha: String,
    val bytes: Long,
    val video: Boolean,
)

private class ProductionPerformanceCampaign(
    private val report: ProductionPerformanceReport,
    private val heldSources: MutableSet<File>,
) {
    val failures = mutableListOf<String>()

    fun automatic(
        input: PerformanceInput,
        iteration: Int,
        waveform: Boolean,
    ) {
        val operation = if (waveform) "waveform" else "probe"
        val row = report.row(input, operation, iteration, false)
        val options = JSONObject().put("poster", !waveform && input.video)
        options.put("edge", if (waveform) JSONObject.NULL else 256)
        row.put("options", options)
        row.put("automatic_worker_memory", "external_trace_required")
        val previous = IsolatedMediaDecoder.lastAvIsolation
        row.put("start_elapsed_ns", SystemClock.elapsedRealtimeNanos())
        try {
            if (waveform) recordWaveform(input, row) else recordProbe(input, row)

            // Automatic completion already includes worker retirement; this fresh receipt binds its UID interval.
            val worker = requireNotNull(IsolatedMediaDecoder.lastAvIsolation)
            check(worker !== previous && worker.uid != Process.myUid() && worker.isolated)
            check(!worker.internetGranted && !worker.runtimeInitialized && worker.privateCanaryDenied)
            check(worker.inputWriteErrno == OsConstants.EBADF)
            row.put("attested_worker_uid", worker.uid)
            row.put("outcome", "observed")
        } catch (failure: Exception) {
            failed(row, input, operation, failure)
        } finally {
            recordAutomaticIdentity(row, previous)
            row.put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            report.emit(row)
        }
    }

    private fun recordWaveform(
        input: PerformanceInput,
        row: JSONObject,
    ) {
        val returned = IsolatedMediaDecoder.waveform(input.file)
        row.put("return_elapsed_ns", SystemClock.elapsedRealtimeNanos())
        val values = requireNotNull(returned) { "Waveform refused" }
        check(values.size == 64 && values.all { it.isFinite() && it in 0f..1f })
        row.put("waveform_bars", values.size)
    }

    private fun recordProbe(
        input: PerformanceInput,
        row: JSONObject,
    ) {
        val returned = IsolatedMediaDecoder.probe(input.file, 256, input.video)
        row.put("return_elapsed_ns", SystemClock.elapsedRealtimeNanos())
        val probe = requireNotNull(returned) { "Probe refused" }
        try {
            check(probe.durationMs > 0)
            if (input.video) check(probe.poster != null)
            row.put("duration_us", probe.durationMs * 1000)
            row.put("poster_pixels", probe.poster?.let { it.width.toLong() * it.height } ?: 0)
        } finally {
            probe.poster?.recycle()
        }
    }

    private fun recordAutomaticIdentity(
        row: JSONObject,
        previous: WorkerIsolation?,
    ) {
        val observed = IsolatedMediaDecoder.lastAvIsolation
        if (observed === previous || observed == null) {
            row.put("automatic_identity_status", "unavailable")
            return
        }
        if (observed.isolated && observed.uid != Process.myUid()) {
            row.put("attested_worker_uid", observed.uid)
            row.put("automatic_identity_status", "fresh_authenticated_receipt")
        } else {
            row.put("automatic_identity_status", "unavailable")
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One row owns counts, timings and exact stream retirement.
    fun stream(
        input: PerformanceInput,
        iteration: Int,
        sampled: Boolean,
    ) {
        val row = report.row(input, if (sampled) "stream_memory" else "stream_decode", iteration, sampled)
        row.put("options", JSONObject().put("video", input.video).put("audio", true).put("edge", 4096))
        val points = JSONArray()
        row.put("memory_points", points)
        var stream: IsolatedAvStream? = null
        var videoFrames = 0L
        var videoPixels = 0L
        var pcmFrames = 0L
        var pcmRate = 0
        var pcmChannels = 0
        if (sampled) points.put(callerMemory("before_open"))
        row.put("start_elapsed_ns", SystemClock.elapsedRealtimeNanos())
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val opened = IsolatedAvStream.openFile(context, input.file, wantVideo = input.video, wantAudio = true)
            stream = opened
            val identity = streamIdentity(opened)
            check(identity.first != Process.myUid() && identity.second > 0 && identity.second != Process.myPid())
            row.put("attested_worker_uid", identity.first).put("attested_worker_pid", identity.second)
            row.put("duration_us", opened.info.durationUs)
            if (sampled) points.put(readyPidMemory("after_open", identity))
            var first = true
            val readDeadline = SystemClock.elapsedRealtime() + 60_000
            while (true) {
                check(SystemClock.elapsedRealtime() < readDeadline) { "Finite corpus decode deadline exceeded" }
                val output = opened.read()
                val receivedNs = SystemClock.elapsedRealtimeNanos()
                when (output) {
                    is AvStreamFrame.Video -> {
                        try {
                            videoFrames++
                            videoPixels += output.bitmap.width.toLong() * output.bitmap.height
                        } finally {
                            output.bitmap.recycle()
                        }
                    }
                    is AvStreamFrame.Audio -> {
                        check(pcmRate == 0 || (pcmRate == output.sampleRate && pcmChannels == output.channels))
                        pcmRate = output.sampleRate
                        pcmChannels = output.channels
                        pcmFrames += output.bytes.size / (output.channels * 2)
                    }
                    AvStreamFrame.End -> {
                        row.put("reached_eof", true).put("eof_elapsed_ns", receivedNs)
                        break
                    }
                    else -> error("ANY read returned a selected-track EOF")
                }
                if (first) {
                    row.put("first_output_elapsed_ns", receivedNs)
                    if (sampled) {
                        points.put(callerMemory("after_first_output"))
                        points.put(readyPidMemory("after_first_output", identity))
                    }
                    first = false
                }
            }
            check(!first && (videoFrames > 0 || pcmFrames > 0))
            if (input.video) {
                val expected = if (input.name == "original-video.mp4") 300L else 5L
                check(videoFrames == expected) { "Unexpected decoded video-frame count" }
                val pixelsPerFrame = if (expected == 300L) 320L * 240 else 64L * 48
                check(videoPixels == expected * pixelsPerFrame) { "Unexpected decoded pixel count" }
            }
            if (!input.video || input.name == "original-video.mp4") {
                check(pcmFrames > 0 && pcmChannels == 1) { "Missing or changed decoded PCM" }
                val expectedRate =
                    when (input.name) {
                        "original-video.mp4" -> 44100
                        "original-wave.wav" -> 16000
                        else -> 48000
                    }
                check(pcmRate == expectedRate) { "Unexpected decoded PCM rate" }
                if (input.name == "original-wave.wav") check(pcmFrames == 3200L)
                if (input.name == "tone-pcm.wav") check(pcmFrames == 48000L)
            } else {
                check(pcmFrames == 0L) { "Silent video unexpectedly produced PCM" }
            }
            if (sampled) {
                points.put(callerMemory("after_eof"))
                points.put(readyPidMemory("after_eof", identity))
            }
            row.put("outcome", "observed")
        } catch (failure: Exception) {
            if (stream == null) {
                heldSources += input.file
                row.put("source_cleanup", "held_open_failure_retirement_unobserved")
            }
            failed(row, input, "stream", failure)
        } finally {
            row.put("video_frames", videoFrames).put("video_pixels", videoPixels).put("pcm_frames", pcmFrames)
            row.put("pcm_rate", if (pcmRate == 0) JSONObject.NULL else pcmRate)
            row.put("pcm_channels", if (pcmChannels == 0) JSONObject.NULL else pcmChannels)
            val retireStart = SystemClock.elapsedRealtimeNanos()
            row.put("retirement_start_elapsed_ns", retireStart)
            if (stream != null) {
                try {
                    stream.close()
                    val retired = stream.awaitRetired()
                    row.put("retirement_confirmed", retired)
                    check(retired) { "Stream retirement remains unconfirmed" }
                } catch (failure: Exception) {
                    heldSources += input.file
                    row.put("source_cleanup", "held_retirement_unconfirmed")
                    failed(row, input, "retirement", failure)
                }
            }
            row.put("retirement_end_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            row.put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos())
            report.emit(row)
        }
    }

    private fun failed(
        row: JSONObject,
        input: PerformanceInput,
        operation: String,
        failure: Exception,
    ) {
        row.put("outcome", "failed").put("error", "${failure.javaClass.simpleName}: ${failure.message}")
        failures += "${input.name}/$operation: ${failure.javaClass.simpleName}: ${failure.message}"
    }
}

private fun streamIdentity(stream: IsolatedAvStream): Pair<Int, Int> {
    val lock =
        IsolatedAvStream::class.java
            .getDeclaredField("stateLock")
            .apply { isAccessible = true }
            .get(stream)
    return synchronized(lock) {
        val value =
            IsolatedAvStream::class.java
                .getDeclaredField("readyIdentity")
                .apply { isAccessible = true }
                .get(stream)
        val pair = value as? Pair<*, *> ?: error("Missing calibrated stream identity")
        (pair.first as? Int ?: error("Invalid worker UID")) to (pair.second as? Int ?: error("Invalid worker PID"))
    }
}

private fun callerMemory(phase: String): JSONObject {
    val point = JSONObject().put("phase", phase).put("process", "caller").put("sample_kind", "point")
    point.put("start_elapsed_ns", SystemClock.elapsedRealtimeNanos())
    point.put("total_pss_kb", JSONObject.NULL).put("native_pss_kb", JSONObject.NULL)
    point.put("native_heap_allocated_bytes", JSONObject.NULL).put("java_heap_used_bytes", JSONObject.NULL)
    point.put("error", JSONObject.NULL)
    try {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        check(info.totalPss > 0) { "Caller meminfo unavailable" }
        val runtime = Runtime.getRuntime()
        point.put("uid", Process.myUid()).put("pid", Process.myPid())
        point.put("total_pss_kb", info.totalPss).put("native_pss_kb", info.nativePss)
        point.put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
        point.put("java_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
        point.put("status", "point_observed_not_peak")
    } catch (failure: Exception) {
        point.put("status", "unavailable").put("error", failure.javaClass.simpleName)
    }
    return point.put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos())
}

/** A PID snapshot is not attributed to the READY worker until an external lifetime trace is joined. */
private fun readyPidMemory(
    phase: String,
    identity: Pair<Int, Int>,
): JSONObject {
    val point = JSONObject().put("phase", phase).put("process", "unverified_ready_pid_sample")
    point.put("sample_kind", "point")
    point.put("expected_ready_uid", identity.first).put("requested_pid", identity.second)
    point.put("actual_sample_uid", JSONObject.NULL)
    point.put("total_pss_kb", JSONObject.NULL).put("total_rss_kb", JSONObject.NULL)
    point.put("native_heap_pss_kb", JSONObject.NULL).put("status", "unavailable").put("error", JSONObject.NULL)
    point.put("attribution", "unverified_requires_lifetime_trace_join")
    point.put("start_elapsed_ns", SystemClock.elapsedRealtimeNanos())
    try {
        check(identity.second > 0 && identity.second != Process.myPid())
        val text = boundedMeminfo(identity.second)
        val pid =
            Regex("\\*\\* MEMINFO in pid (\\d+)")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        check(pid == identity.second) { "meminfo PID header mismatch" }

        fun scalar(pattern: String): Long? =
            Regex(pattern)
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.replace(",", "")
                ?.toLongOrNull()
        val pss = scalar("TOTAL PSS:\\s*([\\d,]+)")?.takeIf { it > 0 }
        point.put("total_pss_kb", pss ?: JSONObject.NULL)
        point.put("total_rss_kb", scalar("TOTAL RSS:\\s*([\\d,]+)") ?: JSONObject.NULL)
        point.put("native_heap_pss_kb", scalar("(?m)^\\s*Native Heap:\\s*([\\d,]+)") ?: JSONObject.NULL)
        point.put("status", if (pss == null) "unavailable" else "point_sample_attribution_unverified")
        if (pss == null) point.put("error", "Unrecognized meminfo PSS format")
    } catch (failure: Exception) {
        point.put("error", "${failure.javaClass.simpleName}: ${failure.message}")
    }
    return point.put("end_elapsed_ns", SystemClock.elapsedRealtimeNanos())
}

/** The shell owns its five-second timeout; no sampler thread or unrelated process search is created. */
private fun boundedMeminfo(pid: Int): String {
    check(pid > 0 && pid != Process.myPid())
    val descriptor =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand("timeout 5 dumpsys meminfo $pid")
    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val count = input.read(block, 0, minOf(block.size, 131073 - output.size()))
            if (count < 0) break
            check(output.size() + count <= 131072) { "meminfo exceeds bounded output" }
            output.write(block, 0, count)
        }
        output.toString("UTF-8")
    }
}

private class ProductionPerformanceReport : Closeable {
    private val token = UUID.randomUUID().toString()
    private var measurementRows = 0
    private val output =
        run {
            val path = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
            val directory = File(path)
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "media-av-production-performance.jsonl")
            check(file.createNewFile()) { "Preserve the earlier performance observation" }
            file.bufferedWriter()
        }

    fun row(
        input: PerformanceInput,
        operation: String,
        iteration: Int,
        sampled: Boolean,
    ): JSONObject =
        JSONObject()
            .put("schema", "media_av_production_performance_v1")
            .put("run_token", token)
            .put("production_route", true)
            .put("input", input.name)
            .put("input_sha256", input.sha)
            .put("input_bytes", input.bytes)
            .put("operation", operation)
            .put("iteration", iteration)
            .put("first_request", iteration == 0 && !sampled)
            .put("first_request_scope", "input_operation_series")
            .put("fresh_session", true)
            .put("sampled", sampled)
            .put("paced", false)
            .put("throughput_eligible", !sampled && operation == "stream_decode")
            .put("api", Build.VERSION.SDK_INT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("fingerprint", Build.FINGERPRINT)
            .put("provenance_join", "producer_manifest_required")
            .put("first_output_elapsed_ns", JSONObject.NULL)
            .put("eof_elapsed_ns", JSONObject.NULL)
            .put("return_elapsed_ns", JSONObject.NULL)
            .put("retirement_start_elapsed_ns", JSONObject.NULL)
            .put("retirement_end_elapsed_ns", JSONObject.NULL)
            .put("error", JSONObject.NULL)
            .put("video_frames", 0)
            .put("video_pixels", 0)
            .put("pcm_frames", 0)
            .put("pcm_rate", JSONObject.NULL)
            .put("pcm_channels", JSONObject.NULL)
            .put("duration_us", JSONObject.NULL)
            .put("reached_eof", false)
            .put("attested_worker_uid", JSONObject.NULL)
            .put("attested_worker_pid", JSONObject.NULL)
            .put("retirement_confirmed", JSONObject.NULL)
            .put("outcome", "incomplete")
            .put("memory_qualification", PERFORMANCE_MEMORY_SCOPE)

    fun emit(row: JSONObject) {
        measurementRows++
        write(row)
    }

    fun complete(
        expectedRows: Int,
        failures: Int,
    ) {
        check(measurementRows == expectedRows) { "Missing performance observations" }
        write(
            JSONObject()
                .put("schema", "media_av_production_performance_v1")
                .put("record_type", "completion")
                .put("run_token", token)
                .put("measurement_rows", measurementRows)
                .put("failed_operations", failures)
                .put("outcome", if (failures == 0) "series_complete" else "observations_failed")
                .put("qualification", PERFORMANCE_COMPLETION_SCOPE),
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("media_av_production_performance_complete", "$token:$measurementRows") },
        )
    }

    private fun write(row: JSONObject) {
        val text = row.toString()
        output.write(text)
        output.newLine()
        output.flush()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("media_av_production_performance_json", text) },
        )
    }

    override fun close() = output.close()
}

private fun performanceSha(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val PERFORMANCE_MEMORY_SCOPE =
    "point_samples_only_ready_pid_attribution_and_automatic_memory_" + "require_lifetime_trace"

private const val PERFORMANCE_COMPLETION_SCOPE =
    "stream_lifetime_join_automatic_memory_trace_and_" + "representative_camera_hdr_pending"
