package dev.ipf.whitenoise.android.core

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ui.common.Avatar
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

private const val AVATAR_FIXTURE_COUNT = 48
private const val AVATAR_BENCH_TAG = "WNAvatarBench"

/** Bounded, synthetic list-scroll measurement of both real avatar loaders on a physical device. */
@RunWith(AndroidJUnit4::class)
class AvatarCacheScrollDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Releases only the synthetic loader state; no account or installed app data is cleared. */
    @After
    fun tearDown() {
        AvatarCacheDiagnostics.stop()
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        GroupAvatarImageLoader.clear()
    }

    /** Measures cold, reverse, and repeated passes beyond both 16 MiB decoded-bitmap budgets. */
    @Test
    fun overCapacityProfileAndGroupAvatarScroll() {
        val imageBytes = syntheticAvatarPng()
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> imageBytes }
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            MaterialTheme { FixtureList(listState, imageBytes) }
        }
        AvatarCacheDiagnostics.start()
        val cold = measurePass("cold_down", listState, down = true)
        val reverse = measurePass("reverse_up", listState, down = false)
        val repeated = measurePass("repeat_down", listState, down = true)
        AvatarCacheDiagnostics.stop()

        assertTrue("fixture never exceeded the profile cache", cold.delta.profile.evictions > 0)
        assertTrue("fixture never exceeded the group cache", cold.delta.group.evictions > 0)
        assertTrue("cold profile fetch boundary did not run", cold.delta.profile.fetchCalls > 16)
        assertTrue("cold group fetch boundary did not run", cold.delta.group.fetchCalls > 16)
        Log.i(AVATAR_BENCH_TAG, "completed cold=$cold reverse=$reverse repeated=$repeated")
    }

    /** Repeats the stress fixture with physical touch timing and matched traversal endpoints. */
    @Test
    fun overCapacityDirectTouchScroll() {
        val imageBytes = syntheticAvatarPng()
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> imageBytes }
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            MaterialTheme { FixtureList(listState, imageBytes) }
        }
        AvatarCacheDiagnostics.start()
        val cold = measureDirectPass("cold_down", listState, down = true)
        val reverse = measureDirectPass("reverse_up", listState, down = false)
        val repeated = measureDirectPass("repeat_down", listState, down = true)
        AvatarCacheDiagnostics.stop()
        assertTrue("no profile cache churn", reverse.delta.profile.fetchCalls > 0)
        assertTrue("no group cache churn", reverse.delta.group.fetchCalls > 0)
        Log.i(AVATAR_BENCH_TAG, "direct_completed cold=$cold reverse=$reverse repeated=$repeated")
    }

    /** Measures the same list and gestures without image fetch or decode work. */
    @Test
    fun directTouchScrollWithoutAvatars() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            MaterialTheme { FixtureList(listState, byteArrayOf(), withAvatars = false) }
        }
        val cold = measureDirectPass("control_down", listState, down = true)
        val reverse = measureDirectPass("control_reverse", listState, down = false)
        val repeated = measureDirectPass("control_repeat", listState, down = true)
        Log.i(AVATAR_BENCH_TAG, "control_completed cold=$cold reverse=$reverse repeated=$repeated")
    }

    /** Estimates the cache and frame effect of decoding chat-row images at 256 px. */
    @Test
    fun smallerSourceDirectTouchScroll() {
        val imageBytes = syntheticAvatarPng(256)
        AvatarImageLoader.clear()
        GroupAvatarImageLoader.clear()
        AvatarImageLoader.attachProfileImageFetcher { _, _ -> imageBytes }
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            MaterialTheme { FixtureList(listState, imageBytes) }
        }
        AvatarCacheDiagnostics.start()
        val cold = measureDirectPass("small_down", listState, down = true)
        val reverse = measureDirectPass("small_reverse", listState, down = false)
        val repeated = measureDirectPass("small_repeat", listState, down = true)
        AvatarCacheDiagnostics.stop()
        Log.i(AVATAR_BENCH_TAG, "small_completed cold=$cold reverse=$reverse repeated=$repeated")
    }

    /** Captures all rendered frames while one direction reaches the same list endpoint. */
    private fun measureDirectPass(
        name: String,
        state: LazyListState,
        down: Boolean,
    ): AvatarPassSample {
        val before = AvatarCacheDiagnostics.snapshot()
        val startIndex = composeRule.runOnIdle { state.firstVisibleItemIndex }
        val startedAtMs = SystemClock.elapsedRealtime()
        val frames = WindowFrameSampler(composeRule.activity.window)
        var swipes = 0
        try {
            while (swipes < 12) {
                val index = composeRule.runOnIdle { state.firstVisibleItemIndex }
                if (if (down) index >= AVATAR_FIXTURE_COUNT - 8 else index == 0) break
                directSwipe(down)
                swipes++
            }
            SystemClock.sleep(1_000L)
        } finally {
            frames.close()
        }
        val terminalIndex = composeRule.runOnIdle { state.firstVisibleItemIndex }
        assertTrue("fixture did not traverse the list: $terminalIndex", if (down) terminalIndex >= 32 else terminalIndex == 0)
        return AvatarPassSample(
            name = name,
            startIndex = startIndex,
            endIndex = terminalIndex,
            swipes = swipes,
            elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
            delta = AvatarCacheDiagnostics.snapshot() - before,
            frames = frames.summary(),
            memory = processMemory(),
        ).also { Log.i(AVATAR_BENCH_TAG, "direct $it") }
    }

    /** Injects a 600 ms gesture into the synthetic list at actual device frame timing. */
    private fun directSwipe(down: Boolean) {
        val decor = composeRule.activity.window.decorView
        val x = decor.width / 2f
        val top = decor.height * 0.30f
        val bottom = decor.height * 0.70f
        val startY = if (down) bottom else top
        val endY = if (down) top else bottom
        val automation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
        val startedAt = SystemClock.uptimeMillis()
        repeat(13) { step ->
            val action =
                when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    12 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val event = MotionEvent.obtain(startedAt, startedAt + step * 50L, action, x, startY + (endY - startY) * step / 12f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("touch injection failed", automation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(50L)
        }
        SystemClock.sleep(250L)
    }

    /** Scrolls one direction while recording cache deltas, rendered-frame time, and process memory. */
    private fun measurePass(
        name: String,
        state: LazyListState,
        down: Boolean,
    ): AvatarPassSample {
        val before = AvatarCacheDiagnostics.snapshot()
        val startIndex = composeRule.runOnIdle { state.firstVisibleItemIndex }
        val startedAtMs = SystemClock.elapsedRealtime()
        var swipes = 0
        val frames = WindowFrameSampler(composeRule.activity.window)
        try {
            repeat(24) {
                val index = composeRule.runOnIdle { state.firstVisibleItemIndex }
                val reachedEnd = if (down) index >= AVATAR_FIXTURE_COUNT - 8 else index == 0
                if (reachedEnd) return@repeat
                swipes++
                composeRule.onNodeWithTag("avatar-fixture-list").performTouchInput {
                    val high = Offset(centerX, height * 0.30f)
                    val low = Offset(centerX, height * 0.70f)
                    swipe(if (down) low else high, if (down) high else low, durationMillis = 600)
                }
                composeRule.waitForIdle()
                SystemClock.sleep(150)
            }
            composeRule.waitForIdle()
            SystemClock.sleep(800)
        } finally {
            frames.close()
        }
        val terminalIndex = composeRule.runOnIdle { state.firstVisibleItemIndex }
        assertTrue(
            "fixture did not traverse the list: $terminalIndex",
            if (down) terminalIndex >= 32 else terminalIndex == 0,
        )
        val sample =
            AvatarPassSample(
                name = name,
                startIndex = startIndex,
                endIndex = terminalIndex,
                swipes = swipes,
                elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                delta = AvatarCacheDiagnostics.snapshot() - before,
                frames = frames.summary(),
                memory = processMemory(),
            )
        Log.i(AVATAR_BENCH_TAG, sample.toString())
        return sample
    }
}

/** Uses the same 52 dp avatar surface as a chat row while keeping all identities synthetic. */
@Composable
private fun FixtureList(
    state: LazyListState,
    bytes: ByteArray,
    withAvatars: Boolean = true,
) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("avatar-fixture-list")) {
        items((0 until AVATAR_FIXTURE_COUNT).toList()) { index ->
            Row(modifier = Modifier.fillMaxWidth().height(72.dp)) {
                if (withAvatars) {
                    Avatar(
                        title = "Profile $index",
                        seed = "synthetic-profile-$index",
                        size = 52.dp,
                        pictureUrl = "https://fixture.invalid/profile/$index",
                    )
                } else {
                    Box(Modifier.size(52.dp))
                }
                Spacer(Modifier.width(8.dp))
                if (withAvatars) SyntheticGroupAvatar(index, bytes) else Box(Modifier.size(52.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fixture chat $index", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Exercises the actual group loader and its one-permit decode path. */
@Composable
private fun SyntheticGroupAvatar(
    index: Int,
    bytes: ByteArray,
) {
    val key = "synthetic-group-$index"
    val image by produceState<ImageBitmap?>(GroupAvatarImageLoader.peek(key), key) {
        value = GroupAvatarImageLoader.load(key) { bytes }
    }
    Box(Modifier.size(52.dp)) {
        image?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(52.dp)) }
    }
}

/** One 512 px decoded bitmap occupies about 1 MiB, exceeding each budget at 48 distinct keys. */
private fun syntheticAvatarPng(edgePx: Int = 512): ByteArray {
    val image = Bitmap.createBitmap(edgePx, edgePx, Bitmap.Config.ARGB_8888)
    return try {
        image.eraseColor(android.graphics.Color.rgb(45, 105, 175))
        ByteArrayOutputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
    } finally {
        image.recycle()
    }
}

/** Subtracts counters to keep each scroll direction independently interpretable. */
private operator fun AvatarCacheSnapshot.minus(other: AvatarCacheSnapshot) =
    AvatarCacheSnapshot(
        profile - other.profile,
        group - other.group,
    )

/** Computes the six counter deltas without storing any cache keys. */
private operator fun AvatarCacheCounts.minus(other: AvatarCacheCounts) =
    AvatarCacheCounts(
        hits - other.hits,
        misses - other.misses,
        evictions - other.evictions,
        fetchCalls - other.fetchCalls,
        decodeCalls - other.decodeCalls,
        deduplicated - other.deduplicated,
    )

/** Captured diagnostics for one bounded pass. */
private data class AvatarPassSample(
    val name: String,
    val startIndex: Int,
    val endIndex: Int,
    val swipes: Int,
    val elapsedMs: Long,
    val delta: AvatarCacheSnapshot,
    val frames: FrameSummary,
    val memory: MemorySummary,
)

/** Frame duration distribution for rendered frames only, excluding idle Choreographer wakeups. */
private data class FrameSummary(
    val count: Int,
    val medianMs: Long,
    val p95Ms: Long,
    val maxMs: Long,
)

/** Process memory plus device configuration at the end of a pass. */
private data class MemorySummary(
    val pssKib: Int,
    val nativeHeapBytes: Long,
    val javaHeapBytes: Long,
    val densityDpi: Int,
    val heapClassMib: Int,
    val device: String,
    val android: String,
    val source: String,
)

/** Reads current process memory without examining any other app or user content. */
private fun processMemory(): MemorySummary {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    val runtime = Runtime.getRuntime()
    val context =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext
    val manager = context.getSystemService(ActivityManager::class.java)
    return MemorySummary(
        pssKib = info.totalPss,
        nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        densityDpi = context.resources.displayMetrics.densityDpi,
        heapClassMib = manager.memoryClass,
        device = Build.MODEL,
        android = Build.VERSION.RELEASE,
        source = BuildConfig.APP_SHORT_SHA,
    )
}

/** Samples framework Window frame metrics on a background handler during touch scrolling. */
private class WindowFrameSampler(
    private val window: Window,
) : AutoCloseable {
    private val thread = HandlerThread("avatar-frame-metrics").apply { start() }
    private val durationsMs = CopyOnWriteArrayList<Long>()
    private val listener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val nanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (nanos > 0) durationsMs.add(nanos / 1_000_000)
        }

    init {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    /** Stops the listener before the test reads its bounded frame sample. */
    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        thread.join(1_000)
    }

    /** Reports median, p95, and maximum rendered-frame time. */
    fun summary(): FrameSummary {
        val values = durationsMs.sorted()
        if (values.isEmpty()) return FrameSummary(0, 0, 0, 0)
        return FrameSummary(
            count = values.size,
            medianMs = values[values.size / 2],
            p95Ms = values[((values.size - 1) * 95) / 100],
            maxMs = values.last(),
        )
    }
}
