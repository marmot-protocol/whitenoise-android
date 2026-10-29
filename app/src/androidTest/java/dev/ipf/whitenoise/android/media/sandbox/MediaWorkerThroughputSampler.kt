package dev.ipf.whitenoise.android.media.sandbox

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Samples worker processes through the shell UID, because isolated workers hide their /proc from the app. */
internal class MediaWorkerThroughputSampler : java.io.Closeable {
    private val running = AtomicBoolean(true)
    private val monitor = Any()
    private var maxWorkers = 0
    private var maxZygotes = 0
    private var maxWorkerRssKb = 0L
    private var maxSingleWorkerRssKb = 0L
    private var maxCallerFds = 0
    private var maxCallerRssKb = 0L
    private var minMemAvailableKb = Long.MAX_VALUE
    private var samples = 0
    private val thread =
        Thread {
            while (running.get()) {
                sampleOnce()
                SystemClock.sleep(SAMPLE_INTERVAL_MS)
            }
        }.apply {
            name = "media-throughput-sampler"
            isDaemon = true
            start()
        }

    /** Records one process/FD/RSS observation, failures leave the previous maxima untouched. */
    private fun sampleOnce() {
        val processes = runCatching { shell("ps -A -o RSS,NAME") }.getOrDefault("")
        val workers = decoderProcesses(processes)
        val zygotes = appZygoteCount(processes)
        val fds = callerFdCount()
        val rss = callerStatusKb("VmRSS:")
        val available = memAvailableKb()
        synchronized(monitor) {
            samples++
            maxWorkers = maxOf(maxWorkers, workers.size)
            maxZygotes = maxOf(maxZygotes, zygotes)
            maxWorkerRssKb = maxOf(maxWorkerRssKb, workers.sum())
            maxSingleWorkerRssKb = maxOf(maxSingleWorkerRssKb, workers.maxOrNull() ?: 0L)
            maxCallerFds = maxOf(maxCallerFds, fds ?: 0)
            maxCallerRssKb = maxOf(maxCallerRssKb, rss ?: 0L)
            minMemAvailableKb = minOf(minMemAvailableKb, available ?: Long.MAX_VALUE)
        }
    }

    /** Returns the maxima observed since construction, as one JSON record. */
    fun snapshot(): JSONObject =
        synchronized(monitor) {
            JSONObject()
                .put("samples", samples)
                .put("max_live_image_workers", maxWorkers)
                .put("max_app_zygotes", maxZygotes)
                .put("max_worker_rss_sum_kb", maxWorkerRssKb)
                .put("max_single_worker_rss_kb", maxSingleWorkerRssKb)
                .put("max_caller_fds", maxCallerFds)
                .put("max_caller_rss_kb", maxCallerRssKb)
                .put("min_mem_available_kb", minMemAvailableKb.takeIf { it != Long.MAX_VALUE } ?: JSONObject.NULL)
        }

    /** Stops sampling and waits for the sampling thread to finish its current observation. */
    override fun close() {
        running.set(false)
        thread.join(SAMPLE_JOIN_MS)
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 100L
        private const val SAMPLE_JOIN_MS = 5_000L

        /**
         * RSS (kB) of every live still-image worker of the measured package in one `ps -A -o RSS,NAME` listing.
         * Another installed build's workers, such as a user's own app on a physical device, are not counted.
         */
        fun decoderProcesses(
            listing: String,
            packageName: String = measuredPackage(),
        ): List<Long> =
            listing
                .lineSequence()
                .filter { it.contains("$packageName:media_decoder") }
                .mapNotNull { line ->
                    line
                        .trim()
                        .split(Regex("\\s+"))
                        .firstOrNull()
                        ?.toLongOrNull()
                }.toList()

        /** Number of live application-zygote processes owned by the measured package in one `ps` listing. */
        fun appZygoteCount(
            listing: String,
            packageName: String = measuredPackage(),
        ): Int =
            listing
                .lineSequence()
                .count { it.trim().endsWith("${packageName}_zygote") }

        /** The package under measurement: the instrumentation's target, whatever identity it was built with. */
        fun measuredPackage(): String = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        /** System-wide MemAvailable in kB, the drop across a scenario includes worker memory the app cannot read. */
        fun memAvailableKb(): Long? =
            runCatching {
                File("/proc/meminfo")
                    .readLines()
                    .firstOrNull { it.startsWith("MemAvailable:") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.toLongOrNull()
            }.getOrNull()

        /** Caller FD count, or null when /proc is unreadable. */
        fun callerFdCount(): Int? = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()

        /** One numeric kB field of the caller's /proc/self/status, or null when unavailable. */
        fun callerStatusKb(field: String): Long? =
            runCatching {
                File("/proc/self/status")
                    .readLines()
                    .firstOrNull { it.startsWith(field) }
                    ?.substringAfter(field)
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.toLongOrNull()
            }.getOrNull()

        private val shellLock = Any()

        /**
         * Runs one read-only shell command through UiAutomation and returns its standard output, one caller at a
         * time — `Instrumentation.getUiAutomation` is not thread-safe, and on API 36 a sampler call racing the test
         * thread's first call fails with "Already connected" or leaves a connect pending when the runner finishes.
         */
        fun shell(command: String): String =
            synchronized(shellLock) {
                val descriptor =
                    InstrumentationRegistry
                        .getInstrumentation()
                        .uiAutomation
                        .executeShellCommand(command)
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
            }
    }
}

/** Sends every row before assertions and also writes raw JSONL when AGP supplies additional output. */
internal class MediaWorkerThroughputReport : java.io.Closeable {
    private val output =
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { directory ->
            check(File(directory).isDirectory || File(directory).mkdirs())
            val file = File(directory, "media-worker-throughput-${SystemClock.elapsedRealtime()}.jsonl")
            check(file.createNewFile()) { "Throughput output already exists; preserve the prior observation" }
            file.bufferedWriter()
        }

    /** Emits one JSON record as instrumentation status and, when available, as one JSONL line. */
    fun emit(value: JSONObject) {
        output?.apply {
            write(value.toString())
            newLine()
            flush()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("media_worker_throughput_json", value.toString()) },
        )
    }

    /** Closes the optional JSONL writer. */
    override fun close() {
        output?.close()
    }
}
