package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import androidx.tracing.Trace
import dev.ipf.whitenoise.android.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time from a foreground start to the first input a handler acted on (#3780).
 *
 * [WarmResumeTrace] opens one async section per foreground. Call [firstAcceptedInput]
 * only after a handler has acted on the input; later inputs in that foreground are
 * ignored. A stop or a newer foreground closes a section that saw no input.
 */
internal object WarmResumeInputTrace {
    private val nextCookie = AtomicInteger()
    private var pending: PendingInput? = null

    @Synchronized
    fun foregroundStarted(
        activityToken: Int,
        foregroundEpoch: Int,
    ) {
        if (pending?.matches(activityToken, foregroundEpoch) == true) return
        complete()
        val cookie = nextCookie.incrementAndGet()
        pending = PendingInput(activityToken, foregroundEpoch, cookie)
        Trace.beginAsyncSection(FIRST_ACCEPTED_INPUT_TRACE, cookie)
    }

    @Synchronized
    fun foregroundStopped(
        activityToken: Int,
        foregroundEpoch: Int,
    ) {
        if (pending?.matches(activityToken, foregroundEpoch) == true) complete()
    }

    @Synchronized
    fun firstAcceptedInput(surface: WarmResumeRenderedSurface) {
        val accepted = complete() ?: return
        if (!BuildConfig.DEBUG && !BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS) return
        Log.i(
            "WNWarmResume",
            "activity=${accepted.activityToken} event=first-accepted-input epoch=${accepted.foregroundEpoch} " +
                "surface=${surface.name.lowercase()} uptime_ms=${SystemClock.uptimeMillis()}",
        )
    }

    private fun complete(): PendingInput? =
        pending?.also {
            Trace.endAsyncSection(FIRST_ACCEPTED_INPUT_TRACE, it.cookie)
            pending = null
        }

    private data class PendingInput(
        val activityToken: Int,
        val foregroundEpoch: Int,
        val cookie: Int,
    ) {
        fun matches(
            activityToken: Int,
            foregroundEpoch: Int,
        ): Boolean = this.activityToken == activityToken && this.foregroundEpoch == foregroundEpoch
    }

    private const val FIRST_ACCEPTED_INPUT_TRACE = "WhiteNoise.warmResume.firstAcceptedInput"
}
