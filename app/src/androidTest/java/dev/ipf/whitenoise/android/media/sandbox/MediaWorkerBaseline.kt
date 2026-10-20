package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Characterizes the unchanged direct transport, not a UI cache or a disposable-worker security guarantee. */
internal object MediaWorkerBaseline {
    private const val CASES = 100
    private const val CORPUS_EDGE = 256
    private const val CORPUS_DEADLINE_MS = 90_000L

    fun run() {
        MediaWorkerBaselineReport().use { run(it) }
    }

    private fun run(report: MediaWorkerBaselineReport) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = SystemClock.elapsedRealtimeNanos()
        val corpus = corpus()
        report.emit(header(corpus, SystemClock.elapsedRealtimeNanos() - start))
        val binding = MediaWorkerBaselineBinding(context)
        val constructStart = SystemClock.elapsedRealtimeNanos()
        val transport = IsolatedMediaTransport(binding, "baseline_images")
        report.emit(phase("transport_construct", SystemClock.elapsedRealtimeNanos() - constructStart))
        val samples = mutableListOf<JSONObject>()
        var completed = 0
        var successes = 0
        try {
            val deadline = SystemClock.elapsedRealtime() + CORPUS_DEADLINE_MS
            while (completed < CASES && SystemClock.elapsedRealtime() < deadline) {
                val sample = sample(completed, corpus, transport, binding)
                report.emit(sample)
                samples += sample
                completed++
                if (sample.getString("outcome") == "success") successes++
            }
            val malformed = probe("malformed_input", transport, binding, MediaDecodePolicy.DECODE)
            report.emit(malformed)
            assertFalse(malformed.getBoolean("returned_bitmap"))
            val watchdog = probe("watchdog_stimulus", transport, binding, MediaDecodePolicy.DEBUG_HANG)
            report.emit(watchdog)
            assertFalse(watchdog.getBoolean("returned_bitmap"))
        } finally {
            try {
                val closeStart = SystemClock.elapsedRealtimeNanos()
                transport.close()
                report.emit(phase("transport_close", SystemClock.elapsedRealtimeNanos() - closeStart))
                report.emit(summary(completed, successes, binding, samples))
            } finally {
                binding.closeObservers()
            }
        }
        assertEquals("Baseline corpus did not finish within its bounded observation window", CASES, completed)
        assertEquals("Healthy baseline operations returned refusals or failed results", CASES, successes)
    }

    private fun sample(
        index: Int,
        corpus: List<ByteArray>,
        transport: IsolatedMediaTransport,
        binding: MediaWorkerBaselineBinding,
    ): JSONObject {
        val operation = index % 6
        val before = binding.snapshot()
        val start = SystemClock.elapsedRealtimeNanos()
        val success = perform(operation, corpus, transport)
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        return JSONObject()
            .put("kind", "sample")
            .put("index", index)
            .put("operation", operationName(operation))
            .put("input_sha256", digest(corpus[sourceIndex(operation)]))
            .put("cache", "direct_transport_no_app_pixel_cache")
            .put("transport_phase", if (index == 0) "first_request" else "subsequent_request")
            .put("operation_total_ns", elapsed)
            .put("outcome", if (success) "success" else "no_result")
            .put("null_reason", if (success) JSONObject.NULL else "refusal_or_timeout_not_typed")
            .put("binding_before", before)
            .put("binding_after", binding.snapshot())
            .put("caller_resources", resources())
    }

    private fun perform(
        operation: Int,
        corpus: List<ByteArray>,
        transport: IsolatedMediaTransport,
    ): Boolean =
        when (operation) {
            0 -> validBitmap(transport.decode(corpus[0], 128, true, MediaDecodePolicy.DECODE))
            1 -> validBitmap(transport.decode(corpus[1], 512, true, MediaDecodePolicy.DECODE))
            2 -> transport.inspect(corpus[0])?.let { it.width == CORPUS_EDGE && it.height == CORPUS_EDGE } == true
            3 -> transport.exif(corpus[1]) == 1
            4 -> validBitmap(transport.region(corpus[0], MediaImageRegion(0, 0, 128, 128), 64))
            else -> validBitmap(transport.decode(corpus[2], 64, false, MediaDecodePolicy.SVG))
        }

    private fun validBitmap(bitmap: Bitmap?): Boolean =
        try {
            bitmap != null && bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap?.recycle()
        }

    private fun probe(
        stimulus: String,
        transport: IsolatedMediaTransport,
        binding: MediaWorkerBaselineBinding,
        operation: Int,
    ): JSONObject {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = transport.decode(byteArrayOf(1, 2, 3), 1, false, operation)
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        val returnedBitmap = result != null
        result?.recycle()
        val record =
            JSONObject()
                .put("kind", "negative_probe")
                .put("stimulus", stimulus)
                .put("operation_total_ns", elapsed)
                .put("returned_bitmap", returnedBitmap)
                .put("null_reason", "production_transport_does_not_type_refusal_or_timeout")
                .put("binding", binding.snapshot())
                .put("caller_resources", resources())
        return record
    }

    private fun corpus(): List<ByteArray> {
        val bitmap = Bitmap.createBitmap(CORPUS_EDGE, CORPUS_EDGE, Bitmap.Config.ARGB_8888)
        return try {
            for (y in 0 until CORPUS_EDGE) {
                for (x in 0 until CORPUS_EDGE) bitmap.setPixel(x, y, Color.rgb(x, y, (x + y) % CORPUS_EDGE))
            }
            listOf(
                encoded(bitmap, Bitmap.CompressFormat.PNG),
                encoded(bitmap, Bitmap.CompressFormat.JPEG),
                """<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256">
                <rect width="256" height="256" fill="blue"/></svg>""".toByteArray(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun encoded(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
    ): ByteArray = ByteArrayOutputStream().also { check(bitmap.compress(format, 100, it)) }.toByteArray()

    private fun header(
        corpus: List<ByteArray>,
        elapsed: Long,
    ): JSONObject =
        JSONObject()
            .put("kind", "header")
            .put("schema", 1)
            .put("corpus", "synthetic-gradient-256-and-svg-v1")
            .put("corpus_sha256", org.json.JSONArray(corpus.map(::digest)))
            .put("requested_cases", CASES)
            .put("api", Build.VERSION.SDK_INT)
            .put("package", InstrumentationRegistry.getInstrumentation().targetContext.packageName)
            .put("flavor", dev.ipf.whitenoise.android.BuildConfig.FLAVOR)
            .put("build_type", dev.ipf.whitenoise.android.BuildConfig.BUILD_TYPE)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("corpus_prepare_ns", elapsed)
            .put("caller_resources", resources())
            .put("security_qualification", false)

    private fun summary(
        completed: Int,
        successes: Int,
        binding: MediaWorkerBaselineBinding,
        samples: List<JSONObject>,
    ): JSONObject =
        JSONObject()
            .put("kind", "summary")
            .put("completed_cases", completed)
            .put("successful_cases", successes)
            .put("binding", binding.snapshot())
            .put("caller_resources", resources())
            .put("latency_groups", latencyGroups(samples))
            .put("worker_memory_observed", false)
            .put("security_qualification", false)

    private fun latencyGroups(samples: List<JSONObject>): org.json.JSONArray {
        val groups =
            samples.groupBy {
                Triple(it.getString("operation"), it.getString("outcome"), it.getString("transport_phase"))
            }
        return org.json.JSONArray(
            groups.map { (key, rows) ->
                val values = rows.map { it.getLong("operation_total_ns") }.sorted()
                JSONObject()
                    .put("operation", key.first)
                    .put("outcome", key.second)
                    .put("transport_phase", key.third)
                    .put("count", values.size)
                    .put("p50_ns", values[(values.size * 50 + 99) / 100 - 1])
                    .put("p95_ns", values[(values.size * 95 + 99) / 100 - 1])
                    .put("max_ns", values.last())
            },
        )
    }

    private fun sourceIndex(operation: Int): Int =
        when (operation) {
            1, 3 -> 1
            5 -> 2
            else -> 0
        }

    private fun phase(
        name: String,
        elapsed: Long,
    ): JSONObject = JSONObject().put("kind", "phase").put("phase", name).put("elapsed_ns", elapsed)

    private fun operationName(value: Int): String {
        val operations = listOf("png_thumbnail", "jpeg_viewer", "png_inspect", "jpeg_exif", "png_region", "svg")
        return operations[value]
    }

    private fun digest(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun resources(): JSONObject {
        val start = SystemClock.elapsedRealtimeNanos()
        val rss =
            runCatching { File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") } }.getOrNull()
        val fds = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()
        return JSONObject()
            .put("caller_rss_status", rss ?: JSONObject.NULL)
            .put("caller_fd_count", fds ?: JSONObject.NULL)
            .put("probe_ns", SystemClock.elapsedRealtimeNanos() - start)
    }
}

/** Sends every row before assertions; AGP also pulls this raw JSONL when additional output is available. */
private class MediaWorkerBaselineReport : java.io.Closeable {
    private val output =
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { directory ->
            check(File(directory).isDirectory || File(directory).mkdirs())
            val file = File(directory, "media-worker-baseline.jsonl")
            check(file.createNewFile()) { "Baseline output already exists; preserve the prior observation" }
            file.bufferedWriter()
        }

    fun emit(value: JSONObject) {
        output?.apply {
            write(value.toString())
            newLine()
            flush()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("media_worker_baseline_json", value.toString()) },
        )
    }

    override fun close() {
        output?.close()
    }
}
