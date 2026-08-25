package dev.ipf.whitenoise.android.state

import android.os.Process
import android.util.Log
import androidx.tracing.Trace
import dev.ipf.whitenoise.android.ui.navigation.WarmResumeFirstUsefulSurface
import java.util.concurrent.atomic.AtomicInteger

/** Privacy-safe lifecycle evidence for issue #812 and its Macrobenchmark trace. */
internal object WarmResumeTrace {
    private val nextActivityToken = AtomicInteger()
    private val nextTraceCookie = AtomicInteger()
    private val openTraceCookies = mutableMapOf<ForegroundKey, Int>()

    fun activityCreated(
        recreated: Boolean,
        runtimeGeneration: Int,
    ): Int =
        nextActivityToken.incrementAndGet().also { token ->
            marker(
                token,
                "activity-created",
                "process=${Process.myPid()} runtime_generation=$runtimeGeneration recreated=$recreated",
            )
        }

    @Synchronized
    fun foregroundStarted(
        activityToken: Int,
        foregroundEpoch: Int,
    ) {
        marker(activityToken, "foreground-started", "epoch=$foregroundEpoch")
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

    private fun marker(
        activityToken: Int,
        event: String,
        fields: String = "",
    ) {
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
}
