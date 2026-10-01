package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.os.SystemClock

/** A bounded platform settling window, never a store of messages or a source for replay. */
internal object NotificationGroupWriteVisibility {
    private const val SETTLE_WINDOW_MS = 1_000L
    private var application: Context? = null
    private var lastChildWrite: Long? = null

    /** Called under the group mutation gate, after Android accepts a child write. */
    fun childWritten(context: Context) {
        application = context.applicationContext
        lastChildWrite = SystemClock.elapsedRealtime()
    }

    /** Empty OS snapshots cannot authorize a cascading summary cancel during this window. */
    fun remainingMillis(context: Context): Long {
        val writtenAt = lastChildWrite?.takeIf { application === context.applicationContext } ?: return 0L
        return (SETTLE_WINDOW_MS - (SystemClock.elapsedRealtime() - writtenAt)).coerceAtLeast(0L)
    }
}
