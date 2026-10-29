package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.notifications.NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS
import dev.ipf.whitenoise.android.notifications.decodeNotificationEmojiInterruptibly
import dev.ipf.whitenoise.android.ui.conversation.media.DecodedAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.decodeMessageAttachmentImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * One still-image lane installed into the real facade for a UI-level scenario, observed through a
 * read-only Binder witness. Closing it reinstalls the production default.
 */
internal class UiLane private constructor(
    val name: String,
    val binding: MediaWorkerBaselineBinding,
) : java.io.Closeable {
    /** DECODE messages sent to every worker of this lane so far. */
    fun decodesSent(): Int {
        val endpoints = binding.snapshot().getJSONArray("endpoints")
        return (0 until endpoints.length()).sumOf { endpoints.getJSONObject(it).getInt("decodes") }
    }

    /** Reinstalls the production default; that clears the facade and closes this lane's transport. */
    override fun close() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        binding.closeObservers()
    }

    companion object {
        /**
         * Installs `reuse`, `prewarm2` or `default` (the transport kind the production facade installs,
         * with the same configuration) behind the facade every UI caller uses.
         */
        fun install(name: String): UiLane {
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val binding = MediaWorkerBaselineBinding(target)
            val transport: MediaDecodeTransport =
                when (if (name == "default") defaultKind(target) else name) {
                    "reuse" -> ReusableMediaTransport(binding, "ui_reuse")
                    "prewarm2" -> IsolatedMediaTransport(binding, "ui_prewarm2", spares = 2)
                    else -> error("Unknown UI lane $name")
                }
            IsolatedMediaDecoder.installTestTransport(transport)
            return UiLane(name, binding)
        }

        /** The kind of transport the production facade installs, read from its private field. */
        private fun defaultKind(target: android.content.Context): String {
            IsolatedMediaDecoder.install(target)
            val field = IsolatedMediaDecoder::class.java.getDeclaredField("transport").apply { isAccessible = true }
            return if (field.get(IsolatedMediaDecoder) is IsolatedMediaTransport) "prewarm2" else "reuse"
        }
    }
}

/** Per-tile timing from a real Compose grid: composition start, pixels, failure or cancellation. */
internal class TileLog {
    private val started = ConcurrentHashMap<Int, Long>()
    val loadedNanos = ConcurrentHashMap<Int, Long>()
    val latencies = CopyOnWriteArrayList<Long>()
    val failed = AtomicInteger()
    val cancelled = AtomicInteger()

    /** Records that tile [index] entered composition and asked for pixels. */
    fun start(index: Int) {
        started[index] = SystemClock.elapsedRealtimeNanos()
    }

    /** Records pixels for tile [index]. */
    fun loaded(index: Int) {
        val now = SystemClock.elapsedRealtimeNanos()
        loadedNanos[index] = now
        started[index]?.let { latencies += now - it }
    }

    /** The slowest loaded tile, with its start relative to [origin], so an outlier can be placed in the run. */
    fun slowest(origin: Long): JSONObject? =
        loadedNanos.entries
            .mapNotNull { (index, loaded) -> started[index]?.let { Triple(index, it, loaded - it) } }
            .maxByOrNull { it.third }
            ?.let { (index, start, latency) ->
                JSONObject()
                    .put("index", index)
                    .put("started_after_content_ns", start - origin)
                    .put("latency_ns", latency)
            }
}

/** UI-level scenarios shared by the containment assertions and the manual measurement fixture. */
internal object MediaWorkerUiScenarios {
    private const val GRID_TAG = "media-worker-grid"
    private const val GRID_TILES = 120
    private const val TILE_SOURCE_EDGE = 512
    private const val TILE_EDGE = 256
    private const val MAX_SWIPES = 30
    private const val SETTLE_TIMEOUT_MS = 10_000L
    private const val NOTIFICATIONS = 6
    private const val BURST = 24
    private const val VIEWER_WIDTH = 4000
    private const val VIEWER_HEIGHT = 3000
    private const val FOLLOW_UP_WINDOW_MS = 2_000L

    private val largeSources by lazy {
        jpeg(VIEWER_WIDTH, VIEWER_HEIGHT, LARGE_CANCELLED) to jpeg(VIEWER_WIDTH, VIEWER_HEIGHT, LARGE_NEXT)
    }

    /** Same bound as the notification presenter's avatar fetch (AVATAR_NOTIFICATION_FETCH_TIMEOUT_MS). */
    const val AVATAR_NOTIFICATION_DEADLINE_MS = 2_500L

    /**
     * Scrolls a real three-column Compose grid of distinct JPEG tiles to its end with touch swipes, each
     * tile decoding through the production bubble decode, then waits for every visible tile's pixels.
     */
    fun gridScroll(
        rule: AndroidComposeTestRule<*, ComponentActivity>,
        lane: UiLane,
    ): JSONObject {
        val sources = (0 until GRID_TILES).map { jpeg(TILE_SOURCE_EDGE, TILE_SOURCE_EDGE, it) }
        val log = TileLog()
        lateinit var state: LazyGridState
        val content = SystemClock.elapsedRealtimeNanos()
        rule.setContent {
            state = rememberLazyGridState()
            MaterialTheme { MediaGrid(state, sources, log) }
        }
        rule.waitForIdle()
        val frames = FrameSampler(rule.activity.window)
        val started = SystemClock.elapsedRealtimeNanos()
        var swipes = 0
        try {
            while (swipes < MAX_SWIPES && rule.runOnIdle { state.canScrollForward }) {
                swipes++
                rule.onNodeWithTag(GRID_TAG).performTouchInput {
                    swipe(Offset(centerX, height * 0.75f), Offset(centerX, height * 0.25f), durationMillis = 400)
                }
                SystemClock.sleep(SWIPE_GAP_MS)
            }
        } finally {
            frames.close()
        }
        val scrolled = SystemClock.elapsedRealtimeNanos()
        val visible = rule.runOnIdle { state.layoutInfo.visibleItemsInfo.map { it.index } }
        val deadline = SystemClock.elapsedRealtime() + SETTLE_TIMEOUT_MS
        while (visible.any { !log.loadedNanos.containsKey(it) } && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        val settled = visible.mapNotNull { log.loadedNanos[it] }.maxOrNull()
        return JSONObject()
            .put("scenario", "grid_scroll")
            .put("lane", lane.name)
            .put("tiles", GRID_TILES)
            .put("swipes", swipes)
            .put("reached_end", rule.runOnIdle { !state.canScrollForward })
            .put("loaded", log.latencies.size)
            .put("failed", log.failed.get())
            .put("cancelled", log.cancelled.get())
            .put("tile_latency", percentiles(log.latencies))
            .put("visible_at_end", visible.size)
            .put("visible_unloaded", visible.count { !log.loadedNanos.containsKey(it) })
            .put("settle_after_last_swipe_ns", settled?.let { (it - scrolled).coerceAtLeast(0L) } ?: JSONObject.NULL)
            .put("setup_ns", started - content)
            .put("scroll_ns", scrolled - started)
            .put("slowest_tile", log.slowest(content) ?: JSONObject.NULL)
            .put("frames", frames.summary())
            .put("worker_starts", lane.binding.snapshot().getInt("binding_attempts"))
    }

    /**
     * Notification artwork against its real deadlines: avatar pixels through the notification fetch lane
     * within 2.5 s, and emoji artwork within the 250 ms emoji preparation bound. The lane is warmed and
     * left idle first, as a running app's lane is; the first image after launch is measured separately.
     * Idle, notifications arrive one at a time, 300 ms apart, each with one avatar and one emoji. With
     * [contended], a burst of grid-sized decodes is queued first, as when a media-heavy chat is open, and
     * all notifications arrive at once behind it.
     */
    fun notificationArtwork(
        lane: UiLane,
        contended: Boolean,
        attempt: Int,
    ): JSONObject =
        runBlocking {
            val tiles = (0 until BURST).map { jpeg(TILE_SOURCE_EDGE, TILE_SOURCE_EDGE, 1_000 + attempt * BURST + it) }
            val emoji = (0 until NOTIFICATIONS).map { png(EMOJI_EDGE, 2_000 + attempt * NOTIFICATIONS + it) }
            MediaPipeline.decodeSampledBitmap(png(EMOJI_EDGE, WARM_UP_SEED - attempt), TILE_EDGE)?.recycle()
            delay(IDLE_SETTLE_MS)
            val burst =
                if (contended) {
                    tiles.map { bytes ->
                        async(Dispatchers.IO) { MediaPipeline.decodeSampledBitmap(bytes, TILE_EDGE)?.recycle() }
                    }
                } else {
                    emptyList()
                }
            if (contended) delay(BURST_HEAD_START_MS)
            val avatarTimes = mutableListOf<Long?>()
            val emojiTimes = mutableListOf<Long?>()
            val waves = if (contended) listOf(0 until NOTIFICATIONS) else (0 until NOTIFICATIONS).map { it..it }
            for (wave in waves) {
                val avatars =
                    wave.map { index ->
                        async(Dispatchers.IO) {
                            timed(AVATAR_NOTIFICATION_DEADLINE_MS) {
                                AvatarImageLoader.loadBitmap("https://fixture.invalid/notify/$attempt/$index")
                            }
                        }
                    }
                val emojiResults =
                    wave.map { index ->
                        async(Dispatchers.IO) {
                            timed(NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS) {
                                decodeNotificationEmojiInterruptibly(emoji[index])
                            }
                        }
                    }
                avatarTimes += avatars.awaitAll()
                emojiTimes += emojiResults.awaitAll()
                if (!contended) delay(NOTIFICATION_GAP_MS)
            }
            burst.awaitAll()
            JSONObject()
                .put("scenario", if (contended) "notification_contended" else "notification_idle")
                .put("lane", lane.name)
                .put("avatar_deadline_ms", AVATAR_NOTIFICATION_DEADLINE_MS)
                .put("avatar_hits", avatarTimes.count { it != null })
                .put("avatar_latency", percentiles(avatarTimes.filterNotNull()))
                .put("emoji_deadline_ms", NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS)
                .put("emoji_hits", emojiTimes.count { it != null })
                .put("emoji_latency", percentiles(emojiTimes.filterNotNull()))
                .put("requests", NOTIFICATIONS)
        }

    /**
     * Cancels a 12-megapixel viewer decode once its input has reached a worker, then opens another image,
     * retrying for up to two seconds as a reopened viewer would. Records whether the cancelled decode
     * delivered pixels, how long cancellation took and when the next image arrived.
     */
    fun viewerCancellation(lane: UiLane): JSONObject =
        cancellation(lane, "viewer_cancel", sentPerRequest = 1) { bytes ->
            (
                decodeMessageAttachmentImage(bytes, "image/jpeg", MediaPipeline.VIEWER_MAX_EDGE_PX) as?
                    DecodedAttachmentPresentation.Static
            )?.bitmap
        }

    /** The same cancellation check through the photo editor's preview decode (inspection, then decode). */
    fun editorCancellation(lane: UiLane): JSONObject {
        val renderer = PhotoEditorRenderer()
        return cancellation(lane, "editor_cancel", sentPerRequest = 2) { bytes ->
            renderer.decodePreview(bytes)
        }
    }

    @Suppress("LongMethod") // One linear measured sequence: dispatch, cancel, then the follow-up window.
    private fun cancellation(
        lane: UiLane,
        scenario: String,
        sentPerRequest: Int,
        decode: suspend (ByteArray) -> Bitmap?,
    ): JSONObject =
        runBlocking {
            // Interruption quarantines nothing, so the same two large sources serve every attempt.
            val (cancelled, next) = largeSources
            var delivered = false
            val before = lane.decodesSent()
            val job =
                launch(Dispatchers.Default) {
                    decode(cancelled)?.let {
                        delivered = true
                        it.recycle()
                    }
                }
            val sentBy = SystemClock.elapsedRealtime() + DISPATCH_WAIT_MS
            while (lane.decodesSent() < before + sentPerRequest && SystemClock.elapsedRealtime() < sentBy) {
                delay(1)
            }
            val midDecode = job.isActive && lane.decodesSent() >= before + sentPerRequest
            val cancelAt = SystemClock.elapsedRealtimeNanos()
            job.cancelAndJoin()
            val cancelNs = SystemClock.elapsedRealtimeNanos() - cancelAt
            val followStart = SystemClock.elapsedRealtimeNanos()
            var attempts = 0
            var image: Bitmap? = null
            while (image == null && SystemClock.elapsedRealtimeNanos() - followStart < FOLLOW_UP_WINDOW_MS * NANOS) {
                attempts++
                image = decode(next)
                if (image == null) delay(POLL_MS)
            }
            val followNs = SystemClock.elapsedRealtimeNanos() - followStart
            image?.recycle()
            JSONObject()
                .put("scenario", scenario)
                .put("lane", lane.name)
                .put("cancelled_mid_decode", midDecode)
                .put("cancelled_delivered_pixels", delivered)
                .put("cancel_ns", cancelNs)
                .put("follow_up_success", image != null)
                .put("follow_up_attempts", attempts)
                .put("follow_up_ns", followNs)
        }

    /** Runs [block] under [deadlineMs]; returns its elapsed nanoseconds, or null when it missed or failed. */
    private suspend fun timed(
        deadlineMs: Long,
        block: suspend () -> Bitmap?,
    ): Long? {
        val start = SystemClock.elapsedRealtimeNanos()
        val bitmap = withTimeoutOrNull(deadlineMs) { block() }
        val elapsed = SystemClock.elapsedRealtimeNanos() - start
        bitmap?.recycle()
        return elapsed.takeIf { bitmap != null }
    }

    /** Nearest-rank p50/p95/max in nanoseconds; empty groups report only their count. */
    fun percentiles(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        val result = JSONObject().put("count", sorted.size)
        if (sorted.isEmpty()) return result
        return result
            .put("p50_ns", sorted[(sorted.size * 50 + 99) / 100 - 1])
            .put("p95_ns", sorted[(sorted.size * 95 + 99) / 100 - 1])
            .put("max_ns", sorted.last())
    }

    /** A deterministic gradient JPEG with an index stamp, so every source has distinct bytes. */
    fun jpeg(
        width: Int,
        height: Int,
        index: Int,
    ): ByteArray = encode(width, height, index, Bitmap.CompressFormat.JPEG)

    /** A small deterministic PNG, the shape of a custom emoji. */
    fun png(
        edge: Int,
        index: Int,
    ): ByteArray = encode(edge, edge, index, Bitmap.CompressFormat.PNG)

    private fun encode(
        width: Int,
        height: Int,
        index: Int,
        format: Bitmap.CompressFormat,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val row = IntArray(width)
            for (y in 0 until height) {
                for (x in 0 until width) row[x] = Color.rgb((x + index) % 256, (y + index * 3) % 256, (x + y) % 256)
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
            ByteArrayOutputStream().also { check(bitmap.compress(format, JPEG_QUALITY, it)) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** A grid laid out like the media library, three square tiles per row. */
    @Composable
    private fun MediaGrid(
        state: LazyGridState,
        sources: List<ByteArray>,
        log: TileLog,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = state,
            modifier = Modifier.fillMaxSize().testTag(GRID_TAG),
        ) {
            items(sources.size, key = { it }) { index -> MediaTile(index, sources[index], log) }
        }
    }

    /** One tile decoding through the production bubble decode; leaving composition cancels it. */
    @Composable
    private fun MediaTile(
        index: Int,
        bytes: ByteArray,
        log: TileLog,
    ) {
        val image by produceState<ImageBitmap?>(null, index) {
            log.start(index)
            try {
                val decoded = decodeMessageAttachmentImage(bytes, "image/jpeg", TILE_EDGE)
                val bitmap = (decoded as? DecodedAttachmentPresentation.Static)?.bitmap
                if (bitmap == null) {
                    log.failed.incrementAndGet()
                } else {
                    log.loaded(index)
                    value = bitmap.asImageBitmap()
                }
            } catch (cancelled: CancellationException) {
                log.cancelled.incrementAndGet()
                throw cancelled
            }
        }
        Box(Modifier.aspectRatio(1f).padding(1.dp)) {
            image?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
    }

    private const val GRID_COLUMNS = 3
    private const val SWIPE_GAP_MS = 150L
    private const val POLL_MS = 20L
    private const val BURST_HEAD_START_MS = 20L
    private const val DISPATCH_WAIT_MS = 5_000L
    private const val JPEG_QUALITY = 90
    private const val WARM_UP_SEED = 9_000
    private const val EMOJI_EDGE = 64
    private const val NOTIFICATION_GAP_MS = 300L
    private const val IDLE_SETTLE_MS = 1_000L
    private const val LARGE_CANCELLED = 3_000
    private const val LARGE_NEXT = 4_000
    private const val NANOS = 1_000_000L
}

/** Rendered-frame durations from the window's frame metrics while a scenario scrolls. */
private class FrameSampler(
    private val window: Window,
) : AutoCloseable {
    private val thread = android.os.HandlerThread("media-worker-frames").apply { start() }
    private val durations = CopyOnWriteArrayList<Long>()
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val nanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (nanos > 0) durations += nanos
        }

    init {
        window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(thread.looper))
    }

    /** Stops sampling. */
    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        thread.join(JOIN_MS)
    }

    /** Frame count with p50/p95/max frame time in nanoseconds. */
    fun summary(): JSONObject = MediaWorkerUiScenarios.percentiles(durations.toList())

    private companion object {
        const val JOIN_MS = 1_000L
    }
}
