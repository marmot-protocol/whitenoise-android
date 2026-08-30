package dev.ipf.whitenoise.android.state

import android.os.Process
import android.util.Log
import androidx.tracing.Trace
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeFirstUsefulSurface
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeLifecycleClass
import dev.ipf.whitenoise.android.ui.navigation.warmResumeForegroundLifecycleClass
import java.util.concurrent.atomic.AtomicInteger

/** Privacy-safe lifecycle evidence for issue #812 and its Macrobenchmark trace. */
internal object WarmResumeTrace {
    private val nextActivityToken = AtomicInteger()
    private val nextTraceCookie = AtomicInteger()
    private val openTraceCookies = mutableMapOf<ForegroundKey, Int>()
    private val renderedSurfaceFrames = ArrayDeque<WarmResumeRenderedFrame>()

    fun activityCreated(
        lifecycleClass: WarmResumeLifecycleClass,
        savedStateAvailable: Boolean,
        runtimeGeneration: Int,
    ): Int =
        nextActivityToken.incrementAndGet().also { token ->
            marker(
                token,
                "activity-created",
                "process=${Process.myPid()} runtime_generation=$runtimeGeneration " +
                    "lifecycle_class=${lifecycleClass.name.lowercase()} " +
                    "saved_state_available=$savedStateAvailable",
            )
        }

    @Synchronized
    fun foregroundStarted(
        activityToken: Int,
        foregroundEpoch: Int,
        activityClass: WarmResumeLifecycleClass,
    ) {
        val lifecycleClass = warmResumeForegroundLifecycleClass(activityClass, foregroundEpoch)
        marker(
            activityToken,
            "foreground-started",
            "epoch=$foregroundEpoch lifecycle_class=${lifecycleClass.name.lowercase()}",
        )
        val key = ForegroundKey(activityToken, foregroundEpoch)
        if (key !in openTraceCookies) {
            val cookie = nextTraceCookie.incrementAndGet()
            openTraceCookies[key] = cookie
            Trace.beginAsyncSection(TRACE_SECTION, cookie)
        }
    }

    @Synchronized
    fun foregroundStopped(
        activityToken: Int,
        foregroundEpoch: Int,
    ) {
        marker(activityToken, "foreground-stopped", "epoch=$foregroundEpoch")
        completeTrace(activityToken, foregroundEpoch)
    }

    fun lifecycle(
        activityToken: Int,
        event: String,
    ) {
        marker(activityToken, event)
    }

    fun firstUsefulFrame(
        activityToken: Int,
        foregroundEpoch: Int,
        surface: WarmResumeFirstUsefulSurface,
        localSnapshotReady: Boolean,
    ) {
        marker(
            activityToken,
            "first-useful-frame",
            "epoch=$foregroundEpoch surface=${surface.name.lowercase()} local_snapshot_ready=$localSnapshotReady",
        )
        completeTrace(activityToken, foregroundEpoch)
    }

    fun restorationState(
        activityToken: Int,
        runtimeGeneration: Int,
        bootstrapLocalReady: Boolean,
        lockDecision: String,
        savedRouteAvailable: Boolean,
        localProjectionAvailable: Boolean,
    ) {
        marker(
            activityToken,
            "restoration-state",
            "runtime_generation=$runtimeGeneration bootstrap_local_ready=$bootstrapLocalReady " +
                "lock_decision=$lockDecision saved_route_available=$savedRouteAvailable " +
                "local_projection_available=$localProjectionAvailable",
        )
    }

    @Synchronized
    fun renderedSurfaceFrame(
        activityToken: Int,
        foregroundEpoch: Int,
        surface: WarmResumeRenderedSurface,
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return
        if (renderedSurfaceFrames.size == MAX_RECORDED_SURFACE_FRAMES) {
            renderedSurfaceFrames.removeFirst()
        }
        renderedSurfaceFrames.addLast(
            WarmResumeRenderedFrame(
                activityToken = activityToken,
                foregroundEpoch = foregroundEpoch,
                surface = surface,
            ),
        )
        marker(
            activityToken,
            "rendered-surface-frame",
            "epoch=$foregroundEpoch surface=${surface.name.lowercase()}",
        )
    }

    @Synchronized
    internal fun resetRenderedSurfaceFrames() {
        renderedSurfaceFrames.clear()
    }

    @Synchronized
    internal fun renderedSurfaceFrames(): List<WarmResumeRenderedFrame> = renderedSurfaceFrames.toList()

    private fun marker(
        activityToken: Int,
        event: String,
        fields: String = "",
    ) {
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return
        val suffix = fields.takeIf(String::isNotEmpty)?.let { " $it" }.orEmpty()
        Log.i(TAG, "activity=$activityToken event=$event$suffix")
    }

    @Synchronized
    private fun completeTrace(
        activityToken: Int,
        foregroundEpoch: Int,
    ) {
        openTraceCookies.remove(ForegroundKey(activityToken, foregroundEpoch))?.let { cookie ->
            Trace.endAsyncSection(TRACE_SECTION, cookie)
        }
    }

    private data class ForegroundKey(
        val activityToken: Int,
        val foregroundEpoch: Int,
    )

    private const val TAG = "WNWarmResume"
    private const val TRACE_SECTION = "WhiteNoise.warmResume.firstUsefulFrame"
    private const val MAX_RECORDED_SURFACE_FRAMES = 64
}

internal enum class WarmResumeRenderedSurface {
    AppLock,
    Onboarding,
    StartupLoading,
    FullScreenLoading,
    ChatList,
    Conversation,
    SharePicker,
    InboundRoute,
    Error,
}

internal data class WarmResumeRenderedFrame(
    val activityToken: Int,
    val foregroundEpoch: Int,
    val surface: WarmResumeRenderedSurface,
)
