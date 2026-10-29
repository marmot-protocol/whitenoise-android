package dev.ipf.whitenoise.android.media.sandbox

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Matched still-image lane characterization: the same synthetic corpus, scenarios and APK drive every
 * named transport variant. Results are performance observations, never a security qualification.
 */
internal object MediaWorkerThroughput {
    private const val THUMB_EDGE = 128
    private const val VIEWER_EDGE = 1920
    private const val COLD_REPETITIONS = 8
    private const val COLD_LAUNCH_REPETITIONS = 5
    private const val QUIESCE_TIMEOUT_MS = 20_000L
    private const val QUIESCE_POLL_MS = 250L
    private const val IDLE_THUMBNAILS = 24
    private const val IDLE_THUMBNAIL_GAP_MS = 400L
    private const val IDLE_VIEWERS = 10
    private const val IDLE_VIEWER_GAP_MS = 600L
    private const val BACK_TO_BACK = 60
    private const val SCROLL_ROUNDS = 8
    private const val SCROLL_TILES = 8
    private const val SCROLL_ROUND_MS = 400L
    private const val WARM_SETTLE_MS = 1_000L
    private const val SCENARIO_DEADLINE_S = 120L
    private const val NEW_DOMAINS = 8
    private const val NEW_DOMAIN_GAP_MS = 400L
    private const val MIXED_BURST = 48
    private const val MIXED_CONVERSATIONS = 6
    private const val CHAT_LIST_ROWS = 32
    private const val CHAT_LIST_GROUP_EVERY = 4
    private val BURSTS = listOf(24, 48, 96)

    /** The conversation every single-conversation scenario decodes in. */
    private val HOME = conversation(0)

    /** One account's published profile pictures. */
    private val PROFILES =
        dev.ipf.whitenoise.android.media.MediaTrustDomain
            .PublicProfiles("throughput")
    private val waitSlot = ThreadLocal<LongArray?>()
    private val observer =
        MediaAdmissionObserver { wait, admitted ->
            waitSlot.get()?.let {
                it[0] = wait
                it[1] = if (admitted) 1 else 0
                it[2] = 1
            }
        }

    /** Runs every scenario for each variant in the given order (repeat names for ABBA ordering). */
    fun run(variants: List<String>) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prepared = SystemClock.elapsedRealtimeNanos()
        val corpus = ThroughputCorpus.create()
        MediaWorkerThroughputReport().use { report ->
            report.emit(header(corpus, variants, SystemClock.elapsedRealtimeNanos() - prepared))
            variants.forEachIndexed { pass, variant ->
                coldFirst(report, context, corpus, pass, variant, coldLaunch = true)
                coldFirst(report, context, corpus, pass, variant, coldLaunch = false)
                scenarios().forEach { scenario -> measured(report, context, corpus, pass, variant, scenario) }
            }
            report.emit(
                JSONObject()
                    .put("kind", "footer")
                    .put("caller_vm_hwm_kb", MediaWorkerThroughputSampler.callerStatusKb("VmHWM:") ?: JSONObject.NULL),
            )
        }
    }

    /** Synthetic conversation [index] of the throughput account. */
    private fun conversation(index: Int): dev.ipf.whitenoise.android.media.MediaTrustDomain =
        dev.ipf.whitenoise.android.media.MediaTrustDomain
            .Conversation("throughput", "conversation-$index")

    /**
     * Scenario bodies after warm-up, each records one row per request. Unless a scenario says otherwise, every
     * request belongs to one conversation, so bursts and scroll rounds stay within it.
     */
    private fun scenarios(): List<ThroughputScenario> =
        listOf(
            ThroughputScenario("new_domain") { lane, corpus, rows ->
                // A running lane meets conversations it has not served yet, one at a time.
                repeat(NEW_DOMAINS) { index ->
                    rows += call(lane, "thumbnail", index) { thumbnail(lane, corpus, index, conversation(100 + index)) }
                    SystemClock.sleep(NEW_DOMAIN_GAP_MS)
                }
            },
            ThroughputScenario("mixed_burst") { lane, corpus, rows ->
                concurrent(lane, corpus, rows, MIXED_BURST, rounds = 1, roundMs = 0L) { index ->
                    conversation(1 + index % MIXED_CONVERSATIONS)
                }
            },
            ThroughputScenario("chat_list") { lane, corpus, rows ->
                // A cold chat list: many people's profile pictures and several groups' own images at once.
                concurrent(lane, corpus, rows, CHAT_LIST_ROWS, rounds = 1, roundMs = 0L) { index ->
                    val groupRow = index % CHAT_LIST_GROUP_EVERY == CHAT_LIST_GROUP_EVERY - 1
                    if (groupRow) conversation(200 + index) else PROFILES
                }
            },
            ThroughputScenario("idle_thumbnail") { lane, corpus, rows ->
                repeat(IDLE_THUMBNAILS) { index ->
                    rows += call(lane, "thumbnail", index) { thumbnail(lane, corpus, index, HOME) }
                    SystemClock.sleep(IDLE_THUMBNAIL_GAP_MS)
                }
            },
            ThroughputScenario("idle_viewer") { lane, corpus, rows ->
                repeat(IDLE_VIEWERS) { index ->
                    rows += call(lane, "viewer", index) { viewer(lane, corpus, index) }
                    SystemClock.sleep(IDLE_VIEWER_GAP_MS)
                }
            },
            ThroughputScenario("back_to_back_mix") { lane, corpus, rows ->
                repeat(BACK_TO_BACK) { index ->
                    rows += call(lane, MIX[index % MIX.size], index) { mix(lane, corpus, index) }
                }
            },
        ) +
            BURSTS.map { size ->
                ThroughputScenario("burst_$size") { lane, corpus, rows ->
                    concurrent(lane, corpus, rows, size, rounds = 1, roundMs = 0L)
                }
            } +
            ThroughputScenario("scroll_rounds") { lane, corpus, rows ->
                concurrent(lane, corpus, rows, SCROLL_TILES, SCROLL_ROUNDS, SCROLL_ROUND_MS)
            } +
            ThroughputScenario("chat_list_url") { lane, corpus, rows ->
                // The same cold chat list where every group shows a URL picture, whose host chooses its bytes, so
                // each group image decodes single-use while the people's pictures share the public profiles.
                concurrent(lane, corpus, rows, CHAT_LIST_ROWS, rounds = 1, roundMs = 0L) { index ->
                    val groupRow = index % CHAT_LIST_GROUP_EVERY == CHAT_LIST_GROUP_EVERY - 1
                    if (groupRow) dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique else PROFILES
                }
            }

    /**
     * First-thumbnail latency on a new lane per repetition. With [coldLaunch], each repetition first waits
     * until no image worker or application zygote of this app is alive, as after a fresh launch; otherwise
     * lanes follow each other, so an application zygote started earlier may still be warm.
     */
    @Suppress("LongParameterList") // Report, context, corpus, identity and the launch condition of one scenario.
    private fun coldFirst(
        report: MediaWorkerThroughputReport,
        context: Context,
        corpus: ThroughputCorpus,
        pass: Int,
        variant: String,
        coldLaunch: Boolean,
    ) {
        val scenario = if (coldLaunch) "cold_launch" else "cold_first"
        val rows = mutableListOf<JSONObject>()
        val sampler = MediaWorkerThroughputSampler()
        var starts = 0
        try {
            repeat(if (coldLaunch) COLD_LAUNCH_REPETITIONS else COLD_REPETITIONS) { index ->
                if (coldLaunch) awaitNoWorkerProcesses()
                ThroughputVariants.create(variant, context, observer).use { lane ->
                    rows += call(lane, "thumbnail", index) { thumbnail(lane, corpus, index, HOME) }
                    starts += lane.binding.snapshot().getInt("binding_attempts")
                }
            }
        } finally {
            sampler.close()
        }
        rows.forEach { report.emit(it.put("pass", pass).put("scenario", scenario)) }
        report.emit(summary(variant, pass, scenario, rows, 0L, starts, JSONArray(), sampler.snapshot()))
    }

    /** Waits until `ps` lists no image worker and no application zygote of this app; fails after a bound. */
    private fun awaitNoWorkerProcesses() {
        val deadline = SystemClock.elapsedRealtime() + QUIESCE_TIMEOUT_MS
        while (true) {
            val listing = MediaWorkerThroughputSampler.shell("ps -A -o RSS,NAME")
            val quiet =
                MediaWorkerThroughputSampler.decoderProcesses(listing).isEmpty() &&
                    MediaWorkerThroughputSampler.appZygoteCount(listing) == 0
            if (quiet) return
            check(SystemClock.elapsedRealtime() < deadline) { "Image workers or the app zygote stayed alive" }
            SystemClock.sleep(QUIESCE_POLL_MS)
        }
    }

    /** Warms one lane, settles so a pre-started generation can connect, then measures one scenario. */
    @Suppress("LongParameterList") // Report, context, corpus and identity of one measured scenario.
    private fun measured(
        report: MediaWorkerThroughputReport,
        context: Context,
        corpus: ThroughputCorpus,
        pass: Int,
        variant: String,
        scenario: ThroughputScenario,
    ) {
        val rows = mutableListOf<JSONObject>()
        val lane = ThroughputVariants.create(variant, context, observer)
        val sampler: MediaWorkerThroughputSampler
        val startsBefore: Int
        val started: Long
        val elapsed: Long
        try {
            val warm =
                call(lane, "warmup", -1) {
                    lane.inDomain(HOME) { valid(lane.transport.decode(corpus.mix[0], THUMB_EDGE, false, DECODE)) }
                }
            report.emit(warm.put("scenario", scenario.name).put("pass", pass))
            SystemClock.sleep(WARM_SETTLE_MS)
            startsBefore = lane.binding.snapshot().getInt("binding_attempts")
            sampler = MediaWorkerThroughputSampler()
            started = SystemClock.elapsedRealtimeNanos()
            try {
                scenario.body(lane, corpus, rows)
            } finally {
                elapsed = SystemClock.elapsedRealtimeNanos() - started
                sampler.close()
            }
        } finally {
            lane.close()
        }
        val binding = lane.binding.snapshot()
        val starts = binding.getInt("binding_attempts") - startsBefore
        rows.forEach { report.emit(it.put("pass", pass).put("scenario", scenario.name)) }
        val endpoints = binding.getJSONArray("endpoints")
        report.emit(summary(variant, pass, scenario.name, rows, elapsed, starts, endpoints, sampler.snapshot()))
    }

    /**
     * Submits [tiles] concurrent thumbnails per round, rounds [roundMs] apart, and waits for all of them. Each
     * request belongs to [domainOf] its index, the home conversation by default.
     */
    @Suppress("LongParameterList") // Lane, corpus, sink and the shape of one concurrent load.
    private fun concurrent(
        lane: ThroughputLane,
        corpus: ThroughputCorpus,
        rows: MutableList<JSONObject>,
        tiles: Int,
        rounds: Int,
        roundMs: Long,
        domainOf: (Int) -> dev.ipf.whitenoise.android.media.MediaTrustDomain = { HOME },
    ) {
        val pool = Executors.newFixedThreadPool(tiles * rounds)
        try {
            val futures =
                (0 until rounds).flatMap { round ->
                    // Each round's callers are released together, like tiles entering one frame.
                    val gate = CountDownLatch(1)
                    val batch =
                        (0 until tiles).map { tile ->
                            val index = round * tiles + tile
                            pool.submit<JSONObject> {
                                gate.await()
                                call(lane, "thumbnail", index) { thumbnail(lane, corpus, index, domainOf(index)) }
                            }
                        }
                    gate.countDown()
                    if (round < rounds - 1) SystemClock.sleep(roundMs)
                    batch
                }
            futures.forEach { rows += it.get(SCENARIO_DEADLINE_S, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Times one request and joins it with the calling thread's admission observation. */
    private fun call(
        lane: ThroughputLane,
        operation: String,
        index: Int,
        block: () -> Boolean,
    ): JSONObject {
        val slot = LongArray(3)
        waitSlot.set(slot)
        val start = SystemClock.elapsedRealtimeNanos()
        val success =
            try {
                block()
            } finally {
                waitSlot.set(null)
            }
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        val observed = slot[2] == 1L
        return JSONObject()
            .put("kind", "sample")
            .put("variant", lane.variant)
            .put("operation", operation)
            .put("index", index)
            .put("start_elapsed_ns", start)
            .put("latency_ns", elapsed)
            .put("admission_wait_ns", if (observed) slot[0] else JSONObject.NULL)
            .put("admitted", if (observed) slot[1] == 1L else JSONObject.NULL)
            .put("outcome", if (success) "success" else "no_result")
    }

    /** One distinct thumbnail decode, as a request of [domain]. */
    private fun thumbnail(
        lane: ThroughputLane,
        corpus: ThroughputCorpus,
        index: Int,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain,
    ): Boolean {
        val source = corpus.thumbnails[index % corpus.thumbnails.size]
        return lane.inDomain(domain) { valid(lane.transport.decode(source, THUMB_EDGE, false, DECODE)) }
    }

    /** One 1920×1440 viewer decode in the home conversation. */
    private fun viewer(
        lane: ThroughputLane,
        corpus: ThroughputCorpus,
        index: Int,
    ): Boolean =
        lane.inDomain(HOME) {
            valid(lane.transport.decode(corpus.viewers[index % corpus.viewers.size], VIEWER_EDGE, true, DECODE))
        }

    /** The existing baseline's six-operation mix: thumbnail, viewer, inspect, EXIF, region and SVG. */
    private fun mix(
        lane: ThroughputLane,
        corpus: ThroughputCorpus,
        index: Int,
    ): Boolean = lane.inDomain(HOME) { mixInScope(lane.transport, corpus, index) }

    /** One operation of the mix on the calling thread's request domain. */
    private fun mixInScope(
        transport: MediaDecodeTransport,
        corpus: ThroughputCorpus,
        index: Int,
    ): Boolean =
        when (index % MIX.size) {
            0 -> valid(transport.decode(corpus.mix[0], THUMB_EDGE, true, DECODE))
            1 -> valid(transport.decode(corpus.mix[1], 512, true, DECODE))
            2 -> transport.inspect(corpus.mix[0])?.let { it.width == 256 && it.height == 256 } == true
            3 -> transport.exif(corpus.mix[1]) == 1
            4 -> valid(transport.region(corpus.mix[0], MediaImageRegion(0, 0, 128, 128), 64))
            else -> valid(transport.decode(corpus.mix[2], MediaDecodePolicy.SVG_EDGE, false, MediaDecodePolicy.SVG))
        }

    private fun valid(bitmap: Bitmap?): Boolean =
        try {
            bitmap != null && bitmap.width > 0 && bitmap.height > 0
        } finally {
            bitmap?.recycle()
        }

    private fun header(
        corpus: ThroughputCorpus,
        variants: List<String>,
        prepareNs: Long,
    ): JSONObject {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        return JSONObject()
            .put("kind", "header")
            .put("schema", 1)
            .put("corpus", "synthetic-gradient-throughput-v2-domains")
            .put("thumbnail_sha256", digest(corpus.thumbnails.fold(ByteArray(0)) { all, next -> all + next }))
            .put("viewer_sha256", digest(corpus.viewers.fold(ByteArray(0)) { all, next -> all + next }))
            .put("mix_sha256", JSONArray(corpus.mix.map(::digest)))
            .put("variants", JSONArray(variants))
            .put("api", Build.VERSION.SDK_INT)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("build_type", dev.ipf.whitenoise.android.BuildConfig.BUILD_TYPE)
            .put("application_debuggable", app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("cpus", Runtime.getRuntime().availableProcessors())
            .put("corpus_prepare_ns", prepareNs)
            .put("security_qualification", false)
    }

    @Suppress("LongParameterList") // One flat summary record per variant, pass and scenario.
    private fun summary(
        variant: String,
        pass: Int,
        scenario: String,
        rows: List<JSONObject>,
        elapsed: Long,
        starts: Int,
        endpoints: JSONArray,
        resources: JSONObject,
    ): JSONObject {
        val latencies = rows.map { it.getLong("latency_ns") }
        val successes = rows.filter { it.getString("outcome") == "success" }.map { it.getLong("latency_ns") }
        val waits = rows.filter { !it.isNull("admission_wait_ns") }.map { it.getLong("admission_wait_ns") }
        val connects =
            (0 until endpoints.length()).map { endpoints.getJSONObject(it).getLong("bind_to_callback_ns") }
        return JSONObject()
            .put("kind", "scenario_summary")
            .put("variant", variant)
            .put("pass", pass)
            .put("scenario", scenario)
            .put("requests", rows.size)
            .put("successes", successes.size)
            .put("no_result", rows.size - successes.size)
            .put("admission_timeouts", rows.count { !it.isNull("admitted") && !it.getBoolean("admitted") })
            .put("makespan_ns", elapsed)
            .put("latency_all", percentiles(latencies))
            .put("latency_success", percentiles(successes))
            .put("admission_wait", percentiles(waits))
            .put("worker_starts", starts)
            .put("bind_to_connect", percentiles(connects))
            .put("resources", resources)
    }

    /** Nearest-rank p50/p95/max in nanoseconds, empty groups report only their count. */
    private fun percentiles(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        val result = JSONObject().put("count", sorted.size)
        if (sorted.isEmpty()) return result
        return result
            .put("p50_ns", sorted[(sorted.size * 50 + 99) / 100 - 1])
            .put("p95_ns", sorted[(sorted.size * 95 + 99) / 100 - 1])
            .put("max_ns", sorted.last())
    }

    private fun digest(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private const val DECODE = MediaDecodePolicy.DECODE
    private val MIX = listOf("png_thumbnail", "jpeg_viewer", "png_inspect", "jpeg_exif", "png_region", "svg")
}

/** One named measured scenario, its body records one row per request. */
private class ThroughputScenario(
    val name: String,
    val body: (ThroughputLane, ThroughputCorpus, MutableList<JSONObject>) -> Unit,
)
