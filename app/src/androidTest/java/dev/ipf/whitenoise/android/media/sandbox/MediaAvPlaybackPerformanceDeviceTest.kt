@file:Suppress("MagicNumber") // Frozen finite campaign sizes and monotonic sampling units.

package dev.ipf.whitenoise.android.media.sandbox

import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Paced real Player/SurfaceView/PCM characterization; all measurements remain subject to trace joins. */
@RunWith(AndroidJUnit4::class)
@MediaContainmentAcceptance
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaAvPlaybackPerformanceDeviceTest {
    private val heldSources = mutableSetOf<File>()

    /** Preserves all ten raw inputs and measures 30 fresh playbacks plus 40 bounded control operations. */
    @Suppress("LongMethod") // One finite campaign owns staging, reporting and fixture cleanup.
    @Test(timeout = 600_000)
    @MediaAvTraceCase
    fun characterizePacedProductionPlaybackOnUnchangedCorpus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val staged = mutableListOf<PerformanceInput>()
        val context = instrumentation.targetContext
        val canary = File(context.filesDir, "media-containment-canary")
        val token = UUID.randomUUID().toString()
        var rows = 0
        var failures = 0
        var canaryOwned = false
        try {
            check(canary.createNewFile()) { "Another fixture owns the canary" }
            canaryOwned = true
            val outputDirectory =
                File(
                    requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
                )
            check(outputDirectory.isDirectory || outputDirectory.mkdirs())
            val output = File(outputDirectory, "media-av-paced-performance.jsonl")
            check(output.createNewFile()) { "Preserve prior paced evidence" }
            canary.writeText("synthetic-paced-performance-canary")
            IsolatedMediaDecoder.install(context)
            MediaAvPerformanceDeviceTest().stageInputs(staged)
            output.bufferedWriter().use { writer ->
                for (input in staged) {
                    repeat(3) { iteration ->
                        val row = playback(input, iteration, controls = false)
                        row.put("run_token", token)
                        writer.write(row.toString())
                        writer.newLine()
                        writer.flush()
                        check(heldSources.isEmpty()) { "Main stalled; source and canary retained" }
                        if (row.getString("outcome") != "observed") failures++
                        rows++
                    }
                }
                // Longest unchanged AV and standalone-audio inputs; no synthetic replacement baseline.
                for (name in listOf("original-video.mp4", "tone-pcm.wav")) {
                    val row = playback(staged.single { it.name == name }, 0, controls = true)
                    row.put("run_token", token)
                    writer.write(row.toString())
                    writer.newLine()
                    writer.flush()
                    check(heldSources.isEmpty()) { "Cleanup unconfirmed; source and canary retained" }
                    if (row.getString("outcome") != "observed") failures++
                    rows++
                }
                check(rows == 32)
                writer.write(
                    JSONObject()
                        .put("record_type", "completion")
                        .put("run_token", token)
                        .put("measurement_rows", rows)
                        .put("startup_observations", 30)
                        .put("resume_observations", 20)
                        .put("seek_observations", 20)
                        .put("failed_rows", failures)
                        .put("qualification", "characterization_only_budgets_and_lifetime_trace_join_pending")
                        .toString(),
                )
                writer.newLine()
            }
            check(failures == 0) { "Paced observations failed; inspect retained JSONL" }
        } finally {
            // Player release is synchronous in every row; trace must independently prove worker retirement.
            check(staged.map { it.file !in heldSources && it.file.delete() }.all { it }) {
                "Paced fixture cleanup incomplete"
            }
            if (canaryOwned) check(canary.delete()) { "Paced canary cleanup incomplete" }
        }
    }

    /** Owns one visible surface and actual production player from creation through synchronous release. */
    @Suppress("LongMethod") // One row owns its actual player, visible Surface and cleanup outcome.
    private fun playback(
        input: PerformanceInput,
        iteration: Int,
        controls: Boolean,
    ): JSONObject {
        val probe = PacedObservation()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val player = AtomicReference<IsolatedAttachmentPlayer?>()
        val failure = AtomicReference<androidx.media3.common.PlaybackException?>()
        val calls = JSONArray()
        val operations = JSONArray()
        val row =
            JSONObject()
                .put("schema", "media_av_paced_performance_v2")
                .put("input", input.name)
                .put("input_sha256", input.sha)
                .put("input_bytes", input.bytes)
                .put("iteration", iteration)
                .put("operation", if (controls) "controls" else "startup_eos")
                .put("fresh_player", true)
                .put("playback_owner", "IsolatedAttachmentPlayer")
                .put("preparation_mode", "attachment_autoplay_optional_probe_not_requested")
                .put("request_order", "play_then_prepare")
                .put("cold_app_process", false)
                .put("cold_filesystem_cache", false)
                .put("paced", true)
                .put("speed", 1)
                .put("muted_real_pcm", true)
                .put("api", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT)
                .put("caller_pid", Process.myPid())
                .put("caller_uid", Process.myUid())
                .put("clock", "elapsedRealtimeNanos")
                .put("provenance_join", "producer_manifest_required")
                .put("physical_presentation", "unsupported_SurfaceView_canvas_post_is_not_presentation")
                .put("queued_pixel_latency", "unmeasured_in_this_timing_campaign_existing_color_test_required")
                .put("acoustic_output", "unmeasured")
                .put("memory", "external_process_stats_sampled_RSS_lifetime_join_required")
                .put("threshold_verdict", "pending_frozen_budget_or_valid_comparator")
        var scenario: ActivityScenario<ComponentActivity>? = null
        var mainStalled = false
        val previousPreparation = IsolatedMediaDecoder.lastAvIsolation
        val startNs = SystemClock.elapsedRealtimeNanos()
        row.put("start_ns", startNs)
        try {
            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                val view = SurfaceView(activity)
                val container = FrameLayout(activity)
                container.addView(view, FrameLayout.LayoutParams(320, 240, Gravity.CENTER))
                activity.setContentView(container)
                val live = IsolatedAttachmentPlayer(context, Uri.fromFile(input.file), probe)
                player.set(live)
                live.addListener(
                    object : Player.Listener {
                        /** Captures the real player error without inferring success from a READY callback. */
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            failure.set(error)
                        }
                    },
                )
                live.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
                live.volume = 0f
                live.setVideoSurfaceView(view)
            }
            row.put("prepare_play_request_ns", SystemClock.elapsedRealtimeNanos())
            sample(player, calls, "prepare_play") { live ->
                live.play()
                live.prepare()
            }
            row.put("prepare_play_return_ns", SystemClock.elapsedRealtimeNanos())
            await(player, failure, calls, probe, "initial_progress") {
                it.currentPosition > 20 || it.playbackState == Player.STATE_ENDED
            }
            row.put("first_media_progress_observed_ns", SystemClock.elapsedRealtimeNanos())
            // This production attachment owner requested raw-header playback, not an automatic poster worker.
            check(IsolatedMediaDecoder.lastAvIsolation === previousPreparation)
            row
                .put("preparation_authenticated_uid", JSONObject.NULL)
                .put("preparation_identity_scope", "not_requested_attachment_autoplay_raw_header")
                .put("optional_preparation_receipt_unchanged", true)
            if (controls) {
                controlSeries(player, failure, calls, operations, probe, input.video)
            } else {
                await(player, failure, calls, probe, "eos") { it.playbackState == Player.STATE_ENDED }
            }
            probe.requireSinks(input.video, !input.video || input.name == "original-video.mp4")
            row.put("outcome", "observed")
        } catch (error: Throwable) {
            row.put("outcome", "failed").put("failure_class", error.javaClass.simpleName)
            mainStalled = error is MainProbeTimeout
        } finally {
            if (mainStalled) {
                heldSources += input.file
                row.put("cleanup", "held_Main_task_completion_unobserved_no_second_task_queued")
            } else {
                finishPlaybackCleanup(input.file, row, {
                    sample(player, calls, "release") { it.release() }
                    player.set(null)
                }, { scenario?.close() })
            }
            row.put("end_ns", SystemClock.elapsedRealtimeNanos()).put("main_calls", calls).put("controls", operations)
            probe.appendTo(row)
        }
        return row
    }

    /** Holds the exact source and canary after uncertain release; no further Main action is admitted. */
    private fun finishPlaybackCleanup(
        source: File,
        row: JSONObject,
        release: () -> Unit,
        closeActivity: () -> Unit,
    ) {
        try {
            release()
            closeActivity()
            row.put("cleanup", "player_release_returned_worker_trace_retirement_pending")
        } catch (failure: Throwable) {
            heldSources += source
            row
                .put("outcome", "failed")
                .put("cleanup_failure_class", failure.javaClass.simpleName)
                .put("cleanup", "held_release_or_activity_cleanup_unconfirmed_no_second_task_queued")
        }
    }

    /** A release timeout must retain fixtures and must never queue activity cleanup behind the unresolved task. */
    @Test fun releaseTimeoutHoldsSourceAndDoesNotCloseActivity() {
        val source = File("synthetic-held-paced-source")
        val row = JSONObject().put("outcome", "observed")
        var closed = false
        finishPlaybackCleanup(source, row, { throw MainProbeTimeout() }, { closed = true })
        check(source in heldSources && !closed)
        check(row.getString("outcome") == "failed")
        check(row.getString("cleanup").startsWith("held_release"))
    }

    /** Alternates interior seeks and ten resume lifetimes; intentional controls are excluded from startup rows. */
    private fun controlSeries(
        player: AtomicReference<IsolatedAttachmentPlayer?>,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
        calls: JSONArray,
        operations: JSONArray,
        probe: PacedObservation,
        video: Boolean,
    ) {
        repeat(10) { index ->
            val previousActivation = probe.latestAudioActivation()
            val previousSeek = probe.latestAudioSeek()
            sample(player, calls, "pause") { it.pause() }
            val request = SystemClock.elapsedRealtimeNanos()
            sample(player, calls, "resume") { it.play() }
            await(player, failure, calls, probe, "resume_audio") {
                probe.hasCausalControlOutput(request, previousActivation, previousSeek, true, video)
            }
            operations.put(
                JSONObject()
                    .put("kind", "resume")
                    .put("iteration", index)
                    .put("request_ns", request)
                    .put("previous_activation", previousActivation)
                    .put("previous_seek", previousSeek)
                    .put("audio_progress_observed_ns", SystemClock.elapsedRealtimeNanos())
                    .put("activation", probe.latestAudioActivation())
                    .put("seek", probe.latestAudioSeek()),
            )
            // Seek early enough to keep the one-second audio fixture away from EOS.
            val target =
                if (video) {
                    if (index % 2 == 0) 1000L else 6500L
                } else {
                    if (index % 2 == 0) 100L else 600L
                }
            val seekRequest = SystemClock.elapsedRealtimeNanos()
            val seekActivation = probe.latestAudioActivation()
            val seekBefore = probe.latestAudioSeek()
            sample(player, calls, "seek") { it.seekTo(target) }
            await(player, failure, calls, probe, "seek_progress") {
                probe.hasCausalControlOutput(seekRequest, seekActivation, seekBefore, false, video) &&
                    it.currentPosition >= target &&
                    it.currentPosition < target + 400
            }
            operations.put(
                JSONObject()
                    .put("kind", "seek")
                    .put("iteration", index)
                    .put("target_ms", target)
                    .put("request_ns", seekRequest)
                    .put("previous_activation", seekActivation)
                    .put("previous_seek", seekBefore)
                    .put("matching_progress_observed_ns", SystemClock.elapsedRealtimeNanos())
                    .put("activation", probe.latestAudioActivation())
                    .put("seek", probe.latestAudioSeek()),
            )
        }
    }

    /** Posts exactly one numbered Main task; records queue delay and getter/control duration even on a stall. */
    private fun sample(
        player: AtomicReference<IsolatedAttachmentPlayer?>,
        calls: JSONArray,
        operation: String,
        action: (IsolatedAttachmentPlayer) -> Unit = {},
    ): Boolean {
        val latch = CountDownLatch(1)
        val posted = SystemClock.elapsedRealtimeNanos()
        val entry = AtomicReference<JSONObject?>()
        val error = AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post {
            val entered = SystemClock.elapsedRealtimeNanos()
            try {
                val live = player.get()
                val getterStart = SystemClock.elapsedRealtimeNanos()
                val state = live?.playbackState
                val position = live?.currentPosition
                val gettersEnd = SystemClock.elapsedRealtimeNanos()
                if (live != null) action(live)
                val returned = SystemClock.elapsedRealtimeNanos()
                entry.set(
                    JSONObject()
                        .put("operation", operation)
                        .put("post_ns", posted)
                        .put("entry_ns", entered)
                        .put("getters_end_ns", gettersEnd)
                        .put("return_ns", returned)
                        .put("state", state)
                        .put("position_ms", position)
                        .put("getter_start_ns", getterStart),
                )
            } catch (caught: Throwable) {
                error.set(caught)
            } finally {
                latch.countDown()
            }
        }
        val completed = latch.await(15, TimeUnit.SECONDS)
        calls.put(entry.get() ?: JSONObject().put("operation", operation).put("post_ns", posted).put("timeout", true))
        if (!completed) throw MainProbeTimeout()
        error.get()?.let { throw it }
        return true
    }

    /** Uses a finite one-outstanding-task cadence; missed periods are retained rather than queued or discarded. */
    @Suppress("LongParameterList") // Explicit fixture owners keep the bounded driver independent of global state.
    private fun await(
        player: AtomicReference<IsolatedAttachmentPlayer?>,
        failure: AtomicReference<androidx.media3.common.PlaybackException?>,
        calls: JSONArray,
        probe: PacedObservation,
        phase: String,
        condition: (IsolatedAttachmentPlayer) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var next = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("Paced player error", it) }
            var satisfied = false
            sample(player, calls, phase) { satisfied = condition(it) }
            player.get()?.observeAudio()
            if (satisfied) return
            next += 20
            val now = SystemClock.elapsedRealtime()
            val skipped = ((now - next).coerceAtLeast(0) / 20).toInt()
            if (skipped > 0) {
                calls.put(
                    JSONObject().put("operation", "skipped_probe_periods").put("phase", phase).put("count", skipped),
                )
            }
            next += skipped * 20L
            SystemClock.sleep((next - SystemClock.elapsedRealtime()).coerceAtLeast(1))
            check(!probe.overflow) { "Bounded scalar observation overflow" }
        }
        error("Paced phase timeout: $phase")
    }
}

/** Fixed scalar arrays keep frame-critical paths allocation-free; JSON is built only after release. */
private class PacedObservation : RawAvPlaybackObservation {
    private val frames = LongArray(8192 * 8)
    private val audio = LongArray(8192 * 6)
    private val canvas = LongArray(8192 * 4)
    private var canvasCount = 0
    private val workers = LongArray(64 * 4)
    private var workerCount = 0
    private var frameCount = 0
    private var audioCount = 0
    private var advances = 0

    @Volatile var overflow = false
        private set

    /** Records actual successful canvas phases; physical presentation is not inferred from posting. */
    @Synchronized override fun canvas(
        acquireStartNs: Long,
        acquiredNs: Long,
        drawEndNs: Long,
        postEndNs: Long,
    ) {
        if (canvasCount == 8192) {
            overflow = true
            return
        }
        val offset = canvasCount++ * 4
        canvas[offset] = acquireStartNs
        canvas[offset + 1] = acquiredNs
        canvas[offset + 2] = drawEndNs
        canvas[offset + 3] = postEndNs
    }

    /** Keeps exact authenticated worker READY identity for external UID/PID/lifetime RSS association. */
    @Synchronized override fun worker(
        activation: Long,
        uid: Int,
        pid: Int,
        timeNs: Long,
    ) {
        if (workerCount == 64) {
            overflow = true
            return
        }
        val offset = workerCount++ * 4
        workers[offset] = activation
        workers[offset + 1] = uid.toLong()
        workers[offset + 2] = pid.toLong()
        workers[offset + 3] = timeNs
    }

    /** Retains actual fenced canvas attempt durations, PTS, clocks and seek/activation identity. */
    @Synchronized override fun submission(
        activation: Long,
        seek: Long,
        ptsUs: Long,
        mediaBeforeUs: Long,
        mediaAfterUs: Long,
        startNs: Long,
        endNs: Long,
        submitted: Boolean,
    ) {
        if (frameCount == 8192) {
            overflow = true
            return
        }
        val offset = frameCount++ * 8
        frames[offset] = activation
        frames[offset + 1] = seek
        frames[offset + 2] = ptsUs
        frames[offset + 3] = mediaBeforeUs
        frames[offset + 4] = mediaAfterUs
        frames[offset + 5] = startNs
        frames[offset + 6] = endNs
        frames[offset + 7] = if (submitted) 1 else 0
    }

    // All identity and head comparisons are required to count advancement within one actual sink lifetime.

    /** Retains the actual AudioTrack counter and detects advancement only within the same sink lifetime. */
    @Suppress("ComplexCondition")
    @Synchronized
    override fun audio(
        activation: Long,
        seek: Long,
        lifetime: Int,
        timeNs: Long,
        hardwareFrames: Long,
        underruns: Int,
    ) {
        if (audioCount == 8192) {
            overflow = true
            return
        }
        val offset = audioCount * 6
        if (audioCount > 0 &&
            audio[offset - 6] == activation &&
            audio[offset - 5] == seek &&
            audio[offset - 4] == lifetime.toLong() &&
            audio[offset - 2] < hardwareFrames
        ) {
            advances++
        }
        audio[offset] = activation
        audio[offset + 1] = seek
        audio[offset + 2] = lifetime.toLong()
        audio[offset + 3] = timeNs
        audio[offset + 4] = hardwareFrames
        audio[offset + 5] = underruns.toLong()
        audioCount++
    }

    /** Returns the renderer-applied audio seek identity, not the UI's requested target. */
    @Synchronized fun latestAudioSeek(): Long = if (audioCount == 0) -1 else audio[(audioCount - 1) * 6 + 1]

    /** Returns only observed same-track head advances, never a cached player-position proxy. */
    @Synchronized fun audioAdvanceCount(): Int = advances

    /** Reads the actual currently sampled renderer activation for operation-to-output association. */
    @Synchronized fun latestAudioActivation(): Long = if (audioCount == 0) -1 else audio[(audioCount - 1) * 6]

    // Keep the causal audio/video proof together; early failures retain every activation, seek and clock check.

    /** Requires advancing audio and an optional matching video post after this exact control request. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    @Synchronized
    fun hasCausalControlOutput(
        requestNs: Long,
        previousActivation: Long,
        previousSeek: Long,
        resume: Boolean,
        videoRequired: Boolean,
    ): Boolean {
        if (audioCount < 2) return false
        val latest = (audioCount - 1) * 6
        val activation = audio[latest]
        val seek = audio[latest + 1]
        if (resume) {
            if (activation <= previousActivation) return false
        } else if (activation != previousActivation || seek <= previousSeek) {
            return false
        }
        var earlierTime = -1L
        var earlierHead = -1L
        var earlierLifetime = -1L
        var progressed = false
        repeat(audioCount) { index ->
            val offset = index * 6
            if (audio[offset] == activation && audio[offset + 1] == seek && audio[offset + 3] >= requestNs) {
                if (audio[offset + 2] == earlierLifetime &&
                    audio[offset + 3] > earlierTime &&
                    audio[offset + 4] > earlierHead
                ) {
                    progressed = true
                }
                earlierLifetime = audio[offset + 2]
                earlierTime = audio[offset + 3]
                earlierHead = audio[offset + 4]
            }
        }
        if (!progressed) return false
        return !videoRequired ||
            (0 until frameCount).any { index ->
                val offset = index * 8
                frames[offset] == activation &&
                    frames[offset + 1] == seek &&
                    frames[offset + 2] >= 0 &&
                    frames[offset + 5] >= requestNs &&
                    frames[offset + 7] == 1L
            }
    }

    /** Refuses complete observations unless the required real sinks produced bounded current-session evidence. */
    @Synchronized fun requireSinks(
        videoRequired: Boolean,
        audioRequired: Boolean,
    ) {
        check(!overflow) { "Scalar evidence overflow" }
        if (videoRequired) {
            check(canvasCount > 0 && (0 until frameCount).any { frames[it * 8 + 2] >= 0 && frames[it * 8 + 7] == 1L }) {
                "Required timed video submission/canvas evidence absent"
            }
        }
        if (audioRequired) {
            check(audioCount >= 2 && advances > 0 && (0 until audioCount).all { audio[it * 6 + 5] >= 0 }) {
                "Required real AudioTrack samples/advancement/underruns absent"
            }
        }
    }

    /** Serializes bounded raw observations after output retirement, preserving unsupported metric labels. */
    @Synchronized fun appendTo(row: JSONObject) {
        check(!overflow) { "Scalar evidence overflow" }
        val frameRows = JSONArray()
        repeat(frameCount) { index ->
            val offset = index * 8
            frameRows.put(JSONArray((0 until 8).map { frames[offset + it] }))
        }
        val audioRows = JSONArray()
        repeat(audioCount) { index -> audioRows.put(JSONArray((0 until 6).map { audio[index * 6 + it] })) }
        val canvasRows = JSONArray()
        repeat(canvasCount) { index -> canvasRows.put(JSONArray((0 until 4).map { canvas[index * 4 + it] })) }
        row
            .put("canvas_columns", "acquire_start_ns,acquired_ns,draw_end_ns,post_end_ns")
            .put("canvas_phases", canvasRows)
            .put("canvas_join", "timestamp_enclosure_in_fenced_submission_attempt")
        val workerRows = JSONArray()
        repeat(workerCount) { index -> workerRows.put(JSONArray((0 until 4).map { workers[index * 4 + it] })) }
        row
            .put("worker_columns", "activation,authenticated_uid,authenticated_pid,ready_observed_ns")
            .put("workers", workerRows)
            .put("worker_retirement", "external_exact_lifetime_trace_required")
        row
            .put(
                "submission_columns",
                "activation,seek,pts_us,media_before_us,media_after_us,start_ns,end_ns,submitted",
            ).put("submissions", frameRows)
            .put("audio_columns", "activation,seek,sink_lifetime,time_ns,extended_head_frames,underruns")
            .put("audio", audioRows)
            .put("audio_head_advances", advances)
            .put("observation_overflow", overflow)
            .put("lateness_definition", "media_after_us - pts_us; exclude pts=-1 posters and intentional controls")
    }
}

/** A timed-out outstanding Main task blocks further task admission and holds fixture cleanup. */
private class MainProbeTimeout : IllegalStateException("Main task timeout; no second task admitted")
