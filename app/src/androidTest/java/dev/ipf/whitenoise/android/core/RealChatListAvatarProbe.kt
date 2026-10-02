package dev.ipf.whitenoise.android.core

import android.app.ActivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

private const val REAL_AVATAR_PROBE_TAG = "WNAvatarRealProbe"

/** Opt-in, identifier-free avatar-cache measurement on an existing authenticated chat list. */
@RunWith(AndroidJUnit4::class)
class RealChatListAvatarProbe {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    /** Stops collection without clearing user data or the newly warmed presentation caches. */
    @After
    fun tearDown() {
        AvatarCacheDiagnostics.stop()
    }

    /** Measures three bounded passes with cache, frame, memory, and whole-app traffic counters. */
    @Test
    fun measureExistingChatList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowRealAvatarProbe") == "true")
        assumeTrue(instrumentation.targetContext.packageName == "dev.ipf.whitenoise.android.dev")
        assumeTrue(Build.MODEL == "Pixel 9 Pro XL")
        composeRule.waitUntil(30_000L) {
            composeRule.onAllNodesWithTag("chats.folders").fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(REAL_AVATAR_PROBE_TAG, "stage=chat_list_ready")

        repeat(5) { swipe(down = false) }
        SystemClock.sleep(1_000L)
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
        AvatarCacheDiagnostics.start()
        val cold = measure("cold_down", down = true)
        val reverse = measure("reverse_up", down = false)
        val repeated = measure("repeat_down", down = true)
        AvatarCacheDiagnostics.stop()
        Log.i(REAL_AVATAR_PROBE_TAG, "complete source=${BuildConfig.APP_SHORT_SHA} cold=$cold reverse=$reverse repeated=$repeated")
        assertTrue("no frames captured", cold.frames.count > 0 && reverse.frames.count > 0 && repeated.frames.count > 0)
    }

    /** Injects a bounded touchscreen gesture without reading or logging the chat-list contents. */
    private fun swipe(down: Boolean) {
        val activity = composeRule.activity
        val width = activity.window.decorView.width
        val height = activity.window.decorView.height
        val x = width / 2
        val top = (height * 0.32f).toInt()
        val bottom = (height * 0.78f).toInt()
        val startY = if (down) bottom else top
        val endY = if (down) top else bottom
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val startedAt = SystemClock.uptimeMillis()
        repeat(13) { step ->
            val action =
                when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    12 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val event =
                MotionEvent.obtain(
                    startedAt,
                    startedAt + step * 50L,
                    action,
                    x.toFloat(),
                    startY + (endY - startY) * step / 12f,
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("touch injection failed", automation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(50L)
        }
        SystemClock.sleep(250L)
    }

    /** Samples one direction without exporting avatar keys, account data, or on-screen text. */
    private fun measure(
        name: String,
        down: Boolean,
    ): RealAvatarPass {
        val before = AvatarCacheDiagnostics.snapshot()
        val rxBefore = TrafficStats.getUidRxBytes(Process.myUid())
        val txBefore = TrafficStats.getUidTxBytes(Process.myUid())
        val frames = RealWindowFrameSampler(composeRule.activity.window)
        val started = SystemClock.elapsedRealtime()
        try {
            repeat(5) { swipe(down) }
            SystemClock.sleep(1_000L)
        } finally {
            frames.close()
        }
        val sample =
            RealAvatarPass(
                name = name,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                cache = AvatarCacheDiagnostics.snapshot().minus(before),
                frames = frames.summary(),
                pssKib = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss,
                nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
                heapClassMib = composeRule.activity.getSystemService(ActivityManager::class.java).memoryClass,
                uidRxBytes = TrafficStats.getUidRxBytes(Process.myUid()).deltaFrom(rxBefore),
                uidTxBytes = TrafficStats.getUidTxBytes(Process.myUid()).deltaFrom(txBefore),
            )
        Log.i(REAL_AVATAR_PROBE_TAG, sample.toString())
        return sample
    }
}

/** Preserves the platform's unsupported-counter sentinel instead of manufacturing a zero. */
private fun Long.deltaFrom(before: Long) = if (this >= 0 && before >= 0) this - before else -1L

/** Per-pass cache and process-wide measurements; traffic includes any concurrent relay activity. */
private data class RealAvatarPass(
    val name: String,
    val elapsedMs: Long,
    val cache: AvatarCacheSnapshot,
    val frames: RealFrameSummary,
    val pssKib: Int,
    val nativeHeapBytes: Long,
    val heapClassMib: Int,
    val uidRxBytes: Long,
    val uidTxBytes: Long,
)

/** Subtracts only aggregate counters, without retaining cache keys. */
private fun AvatarCacheSnapshot.minus(other: AvatarCacheSnapshot) = AvatarCacheSnapshot(profile.minus(other.profile), group.minus(other.group))

/** Calculates one cache's hit, miss, capacity-eviction, and fetch deltas. */
private fun AvatarCacheCounts.minus(other: AvatarCacheCounts) =
    AvatarCacheCounts(
        hits - other.hits,
        misses - other.misses,
        evictions - other.evictions,
        fetchCalls - other.fetchCalls,
        decodeCalls - other.decodeCalls,
        deduplicated - other.deduplicated,
    )

/** Distribution of rendered Window frames during one pass. */
private data class RealFrameSummary(
    val count: Int,
    val medianMs: Long,
    val p95Ms: Long,
    val maxMs: Long,
)

/** Samples framework frame durations while gestures run. */
private class RealWindowFrameSampler(
    private val window: Window,
) : AutoCloseable {
    private val thread = HandlerThread("real-avatar-frames").apply { start() }
    private val durations = CopyOnWriteArrayList<Long>()
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (duration > 0) durations += duration / 1_000_000L
        }

    init {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        thread.join(1_000L)
    }

    /** Returns nearest-rank percentiles after the listener is detached. */
    fun summary(): RealFrameSummary {
        val sorted = durations.sorted()
        if (sorted.isEmpty()) return RealFrameSummary(0, 0, 0, 0)
        return RealFrameSummary(sorted.size, sorted[sorted.size / 2], sorted[((sorted.size * 0.95).toInt()).coerceAtMost(sorted.lastIndex)], sorted.last())
    }
}
