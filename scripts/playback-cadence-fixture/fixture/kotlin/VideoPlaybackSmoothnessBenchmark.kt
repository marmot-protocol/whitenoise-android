@file:Suppress("MagicNumber") // Fixed clip geometry, frame rates and percentile constants.

package dev.ipf.whitenoise.android.media.sandbox

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Measures how smoothly the production isolated player presents video on a full-screen SurfaceView.
 *
 * Score per clip: the share of content frames that were not posted within one frame interval of their
 * presentation time on the playback clock (late or never shown). Lower is better; zero is perfectly
 * smooth. The run is manual-only: `am instrument -e class ...VideoPlaybackSmoothnessBenchmark`.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlaybackSmoothnessBenchmark {
    private data class Clip(
        val name: String,
        val fps: Int,
        val frames: Int,
    )

    private val clips =
        listOf(
            Clip("landscape-1080p30.mp4", 30, 180),
            Clip("portrait-1080p30-r90.mp4", 30, 180),
            Clip("landscape-1080p60.mp4", 60, 360),
            Clip("low-delay-av1-aac-60.mp4", 60, 360),
        )

    @Test(timeout = 600_000)
    fun measurePlaybackSmoothness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repeats = InstrumentationRegistry.getArguments().getString("repeats")?.toInt() ?: 3
        IsolatedMediaDecoder.install(context)
        val results = JSONArray()
        for (clip in clips) {
            val bytes = instrumentation.context.assets.open("video-smoothness/${clip.name}").use { it.readBytes() }
            val file = File.createTempFile("smoothness-", ".mp4", context.cacheDir)
            try {
                file.writeBytes(bytes)
                // The first run warms the worker process, page cache and code; it is reported but not scored.
                repeat(repeats + 1) { run ->
                    val row = play(clip, file).put("clip", clip.name).put("run", run).put("warmup", run == 0)
                    Log.i(TAG, row.toString())
                    results.put(row)
                }
            } finally {
                file.delete()
            }
        }
        val summary = summarize(results)
        Log.i(TAG, "SUMMARY $summary")
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putString("smoothness", summary.toString())
                putString("smoothness_runs", results.toString())
            },
        )
    }

    private fun play(
        clip: Clip,
        file: File,
    ): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = SubmissionRecorder()
        val player = AtomicReference<IsolatedAttachmentPlayer?>()
        val failure = AtomicReference<PlaybackException?>()
        val ended = CountDownLatch(1)
        var scenario: ActivityScenario<ComponentActivity>? = null
        val started: Long
        val audioSampler = Executors.newSingleThreadScheduledExecutor()
        try {
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val view = SurfaceView(activity)
                activity.setContentView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                val live = IsolatedAttachmentPlayer(context, Uri.fromFile(file), probe)
                player.set(live)
                live.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failure.set(error)
                            ended.countDown()
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) ended.countDown()
                        }
                    },
                )
                live.volume = 0f
                live.setVideoSurfaceView(view)
            }
            // Let the surface settle so its creation is not attributed to playback.
            SystemClock.sleep(500)
            started = SystemClock.elapsedRealtimeNanos()
            scenario.onActivity {
                val live = checkNotNull(player.get())
                live.play()
                live.prepare()
            }
            val live = checkNotNull(player.get())
            audioSampler.scheduleWithFixedDelay(live::observeAudio, 0, 10, TimeUnit.MILLISECONDS)
            check(ended.await(60, TimeUnit.SECONDS)) { "Playback did not end" }
            failure.get()?.let { throw it }
        } finally {
            audioSampler.shutdown()
            check(audioSampler.awaitTermination(5, TimeUnit.SECONDS))
            scenario?.onActivity { player.getAndSet(null)?.release() }
            scenario?.close()
        }
        return probe.score(clip, started)
    }

    private fun summarize(results: JSONArray): JSONObject {
        val byClip = linkedMapOf<String, MutableList<JSONObject>>()
        for (i in 0 until results.length()) {
            val row = results.getJSONObject(i)
            if (!row.getBoolean("warmup")) byClip.getOrPut(row.getString("clip")) { mutableListOf() } += row
        }
        val clipsJson = JSONObject()
        var jankSum = 0.0
        for ((name, rows) in byClip) {
            val janks = rows.map { it.getDouble("jank_pct") }.sorted()
            val median = janks[janks.size / 2]
            jankSum += median
            clipsJson.put(
                name,
                JSONObject()
                    .put("jank_pct_median", median)
                    .put("jank_pct_runs", JSONArray(janks))
                    .put("presented_pct_median", rows.map { it.getDouble("presented_pct") }.sorted()[rows.size / 2])
                    .put("late_p95_ms_median", rows.map { it.getDouble("late_p95_ms") }.sorted()[rows.size / 2])
                    .put("gap_p95_ms_median", rows.map { it.getDouble("gap_p95_ms") }.sorted()[rows.size / 2])
                    .put("draw_p50_ms_median", rows.map { it.getDouble("draw_p50_ms") }.sorted()[rows.size / 2]),
            )
        }
        return JSONObject()
            .put("score_jank_pct_mean", if (byClip.isEmpty()) -1.0 else jankSum / byClip.size)
            .put("clips", clipsJson)
    }

    /** Bounded primitive recorder for production submission observations. */
    private class SubmissionRecorder : RawAvPlaybackObservation {
        private val lock = Any()
        private val capacity = 4096
        private val pts = LongArray(capacity)
        private val mediaAfter = LongArray(capacity)
        private val start = LongArray(capacity)
        private val end = LongArray(capacity)
        private var count = 0
        private val hardwareHead = LongArray(capacity)
        private val audioTimeNs = LongArray(capacity)
        private val underrunCounts = IntArray(capacity)
        private val audioSink = IntArray(capacity)
        private var audioCount = 0
        private val acquireNs = LongArray(capacity)
        private val drawNs = LongArray(capacity)
        private val postNs = LongArray(capacity)
        private var canvasCount = 0

        override fun audio(
            activation: Long,
            seek: Long,
            lifetime: Int,
            timeNs: Long,
            hardwareFrames: Long,
            underruns: Int,
        ) {
            synchronized(lock) {
                if (audioCount == capacity) return
                hardwareHead[audioCount] = hardwareFrames
                audioTimeNs[audioCount] = timeNs
                underrunCounts[audioCount] = underruns
                audioSink[audioCount] = lifetime
                audioCount++
            }
        }

        override fun canvas(acquireStartNs: Long, acquiredNs: Long, drawEndNs: Long, postEndNs: Long) {
            synchronized(lock) {
                if (canvasCount == capacity) return
                acquireNs[canvasCount] = acquiredNs - acquireStartNs
                drawNs[canvasCount] = drawEndNs - acquiredNs
                postNs[canvasCount] = postEndNs - drawEndNs
                canvasCount++
            }
        }

        override fun submission(
            activation: Long,
            seek: Long,
            ptsUs: Long,
            mediaBeforeUs: Long,
            mediaAfterUs: Long,
            startNs: Long,
            endNs: Long,
            submitted: Boolean,
        ) {
            if (!submitted || ptsUs < 0) return
            synchronized(lock) {
                if (count == capacity) return
                pts[count] = ptsUs
                mediaAfter[count] = mediaAfterUs
                start[count] = startNs
                end[count] = endNs
                count++
            }
        }

        fun score(
            clip: Clip,
            startedNs: Long,
        ): JSONObject =
            synchronized(lock) {
                val intervalUs = 1_000_000L / clip.fps
                val seen = HashSet<Long>()
                val lateUs = ArrayList<Long>()
                val draws = ArrayList<Long>()
                var onTime = 0
                var lastEnd = -1L
                val gaps = ArrayList<Long>()
                var onTimeByWall = 0
                val firstPostNs = if (count == 0) 0L else end[0]
                val firstPtsUs = if (count == 0) 0L else pts[0]
                val referencePhaseUs = ArrayList<Long>()
                val steadyPhaseUs = ArrayList<Long>()
                val phaseBySecond = List(clip.frames / clip.fps) { ArrayList<Long>() }
                var lastPtsUs = firstPtsUs
                for (i in 0 until count) {
                    draws += end[i] - start[i]
                    if (!seen.add(pts[i])) continue
                    val late = mediaAfter[i] - pts[i]
                    lateUs += late
                    if (late <= intervalUs) onTime++
                    lastPtsUs = maxOf(lastPtsUs, pts[i])
                    val monotonicLateUs = (end[i] - firstPostNs) / 1000L - (pts[i] - firstPtsUs)
                    // Use the second PTS second as the reference and evaluate the last four seconds.
                    // The first second includes the initial poster and AudioTrack startup.
                    val ptsSecond = (pts[i] / 1_000_000L).toInt()
                    phaseBySecond.getOrNull(ptsSecond)?.add(monotonicLateUs)
                    if (ptsSecond == 1) referencePhaseUs += monotonicLateUs
                    if (ptsSecond >= 2) steadyPhaseUs += monotonicLateUs
                    if (monotonicLateUs <= intervalUs) onTimeByWall++
                    if (lastEnd >= 0) gaps += end[i] - lastEnd
                    lastEnd = end[i]
                }
                val presented = seen.size
                val steadyDeadlineMissPct = if (referencePhaseUs.isEmpty()) {
                    JSONObject.NULL
                } else {
                    val phaseReferenceUs = percentile(referencePhaseUs, 50)
                    val steadyExpected = clip.frames - 2 * clip.fps
                    val steadyOnTime = steadyPhaseUs.count { it - phaseReferenceUs <= intervalUs }
                    100.0 * (steadyExpected - steadyOnTime).coerceAtLeast(0) / steadyExpected
                }
                val underrunEvents = JSONArray()
                val totalUnderruns = (1 until audioCount).sumOf { index ->
                    if (audioSink[index] == audioSink[index - 1]) {
                        val delta = (underrunCounts[index] - underrunCounts[index - 1]).coerceAtLeast(0)
                        if (delta > 0 && underrunEvents.length() < 16) {
                            underrunEvents.put(
                                JSONObject()
                                    .put("elapsed_ms", (audioTimeNs[index] - startedNs) / 1e6)
                                    .put("count", delta),
                            )
                        }
                        delta
                    } else {
                        0
                    }
                }
                val headMs = (1 until audioCount)
                    .filter { audioSink[it] == audioSink[it - 1] && hardwareHead[it] != hardwareHead[it - 1] }
                    .map { audioTimeNs[it] }
                    .zipWithNext { previous, current -> current - previous }
                JSONObject()
                    .put("frames_expected", clip.frames)
                    .put("frames_presented", presented)
                    .put("frames_on_time", onTime)
                    .put("jank_pct", 100.0 * (clip.frames - onTime).coerceAtLeast(0) / clip.frames)
                    .put("presented_pct", 100.0 * presented / clip.frames)
                    .put("wall_pacing_miss_pct", 100.0 * (clip.frames - onTimeByWall).coerceAtLeast(0) / clip.frames)
                    .put("startup_first_post_ms", if (count == 0) JSONObject.NULL else (firstPostNs - startedNs) / 1e6)
                    .put("posted_span_ms", if (count == 0) JSONObject.NULL else (lastEnd - firstPostNs) / 1e6)
                    .put("pts_span_ms", if (count == 0) JSONObject.NULL else (lastPtsUs - firstPtsUs) / 1e3)
                    .put(
                        "wall_phase_median_by_pts_second_ms",
                        JSONArray(phaseBySecond.map { if (it.isEmpty()) JSONObject.NULL else percentile(it, 50) / 1e3 }),
                    )
                    .put("steady_deadline_miss_pct", steadyDeadlineMissPct)
                    .put("audio_underrun_events", underrunEvents)
                    .put("audio_samples", audioCount)
                    .put("audio_underruns", totalUnderruns)
                    .put("audio_head_gap_p95_ms", percentile(headMs, 95) / 1e6)
                    .put("canvas_acquire_p95_ms", percentile(acquireNs.take(canvasCount), 95) / 1e6)
                    .put("canvas_draw_p95_ms", percentile(drawNs.take(canvasCount), 95) / 1e6)
                    .put("canvas_post_p95_ms", percentile(postNs.take(canvasCount), 95) / 1e6)
                    .put("late_p50_ms", percentile(lateUs, 50) / 1000.0)
                    .put("late_p95_ms", percentile(lateUs, 95) / 1000.0)
                    .put("late_max_ms", (lateUs.maxOrNull() ?: 0) / 1000.0)
                    .put("gap_p50_ms", percentile(gaps, 50) / 1e6)
                    .put("gap_p95_ms", percentile(gaps, 95) / 1e6)
                    .put("gap_max_ms", (gaps.maxOrNull() ?: 0) / 1e6)
                    .put("draw_p50_ms", percentile(draws, 50) / 1e6)
                    .put("draw_p95_ms", percentile(draws, 95) / 1e6)
                    .put("wall_ms", if (count == 0) 0.0 else (end[count - 1] - startedNs) / 1e6)
            }

        private fun percentile(
            values: List<Long>,
            p: Int,
        ): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[((sorted.size - 1) * p / 100.0).toInt()].toDouble()
        }
    }

    private companion object {
        const val TAG = "VideoSmoothness"
    }
}
