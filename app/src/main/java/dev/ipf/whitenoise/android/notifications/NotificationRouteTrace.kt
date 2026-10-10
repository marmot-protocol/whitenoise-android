package dev.ipf.whitenoise.android.notifications

import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicInteger

internal object NotificationRouteTraceSection {
    const val TOTAL = "WhiteNoise.notificationRoute.total"
    const val ACCOUNT_ACTIVATION = "WhiteNoise.notificationRoute.accountActivation"
    const val TARGET_PROJECTION = "WhiteNoise.notificationRoute.targetProjection"
    const val CONTROLLER_BIND = "WhiteNoise.notificationRoute.controllerBind"
    const val TARGET_TIMELINE = "WhiteNoise.notificationRoute.targetTimeline"
    const val INITIAL_ANCHOR = "WhiteNoise.notificationRoute.initialAnchor"
    const val FIRST_CONVERSATION_FRAME = "WhiteNoise.notificationRoute.firstConversationFrame"

    /** The awaited card dismissal that must finish before the conversation route is published. */
    const val COMMIT_DISMISS = "WhiteNoise.notificationRoute.commitDismiss"

    /** Time the root spent on a loading surface instead of the retained conversation, a regression marker for #586. */
    const val STARTUP_SWAP = "WhiteNoise.notificationRoute.startupSwap"

    /** Human time under app lock, kept out of [TOTAL] so the budget measures active processing only. */
    const val APP_LOCK_WAIT = "WhiteNoise.notificationRoute.appLockWait"

    /** Prefix of the zero-length launch-class marker, followed by one fixed label and never by an identifier. */
    const val LAUNCH_CLASS_PREFIX = "WhiteNoise.notificationRoute.launchClass."
}

/** Where the route's slices are written, so a test can read them back instead of a platform trace. */
internal interface NotificationRouteTraceSink {
    /** Whether anything is listening, so no section is opened for a trace nobody records. */
    fun isEnabled(): Boolean

    /** Opens the async slice [name] under [cookie]. */
    fun beginAsyncSection(
        name: String,
        cookie: Int,
    )

    /** Closes the async slice [name] opened under [cookie]. */
    fun endAsyncSection(
        name: String,
        cookie: Int,
    )

    /** Writes a zero-length slice named [name]. */
    fun instant(name: String)
}

/** The platform trace, which is what production writes. */
private object PlatformNotificationRouteTraceSink : NotificationRouteTraceSink {
    /** Delegates to the platform trace's enabled flag. */
    override fun isEnabled(): Boolean = Trace.isEnabled()

    /** Delegates to the platform async slice. */
    override fun beginAsyncSection(
        name: String,
        cookie: Int,
    ) = Trace.beginAsyncSection(name, cookie)

    /** Delegates to the platform async slice. */
    override fun endAsyncSection(
        name: String,
        cookie: Int,
    ) = Trace.endAsyncSection(name, cookie)

    /** Writes an empty synchronous slice. */
    override fun instant(name: String) {
        Trace.beginSection(name)
        Trace.endSection()
    }
}

/**
 * Process-wide, privacy-safe async slices for notification navigation.
 *
 * Request ids are used only as in-process trace ownership tokens and never
 * appear in section names. Starting a newer request closes every older slice.
 */
internal object NotificationRouteTrace {
    private data class PhaseKey(
        val requestId: Long,
        val sectionName: String,
    )

    private val lock = Any()

    /** Replaced by tests only; production always writes the platform trace. */
    internal var sink: NotificationRouteTraceSink = PlatformNotificationRouteTraceSink
    private val cookieCounter = AtomicInteger()
    private var activeRequest: Pair<Long, Int>? = null

    // Whether the active request's open slice is the lock wait rather than its total.
    private var lockWaiting = false
    private val activePhases = mutableMapOf<PhaseKey, Int>()

    /**
     * Starts the request's total slice and, when known, stamps its fixed [launchClass] label as a
     * zero-length marker so a trace can be bucketed by lifecycle without carrying any identifier.
     */
    fun startRequest(
        requestId: Long,
        launchClass: NotificationRouteLaunchClass? = null,
    ) {
        synchronized(lock) {
            activeRequest?.let { (activeRequestId, cookie) ->
                endRequestLocked(activeRequestId, cookie)
            }
            if (!sink.isEnabled()) {
                activeRequest = null
                return
            }
            val cookie = nextCookie()
            sink.beginAsyncSection(NotificationRouteTraceSection.TOTAL, cookie)
            activeRequest = requestId to cookie
            launchClass?.let {
                sink.instant(NotificationRouteTraceSection.LAUNCH_CLASS_PREFIX + it.traceLabel)
            }
        }
    }

    /**
     * Ends the total slice while the route waits on a person, so a budget measured over it excludes
     * unlock time, and opens the wait as its own slice. A request that is not current is left alone.
     */
    fun pauseForAppLock(requestId: Long) {
        synchronized(lock) {
            val active = activeRequest?.takeIf { it.first == requestId && !lockWaiting } ?: return
            sink.endAsyncSection(NotificationRouteTraceSection.TOTAL, active.second)
            val waitCookie = nextCookie()
            sink.beginAsyncSection(NotificationRouteTraceSection.APP_LOCK_WAIT, waitCookie)
            activeRequest = requestId to waitCookie
            lockWaiting = true
        }
    }

    /** Closes the lock wait that [pauseForAppLock] opened and resumes the total slice for the same request. */
    fun resumeAfterAppLock(requestId: Long) {
        synchronized(lock) {
            val waiting = activeRequest?.takeIf { it.first == requestId && lockWaiting } ?: return
            sink.endAsyncSection(NotificationRouteTraceSection.APP_LOCK_WAIT, waiting.second)
            val cookie = nextCookie()
            sink.beginAsyncSection(NotificationRouteTraceSection.TOTAL, cookie)
            activeRequest = requestId to cookie
            lockWaiting = false
        }
    }

    /** Opens the async slice [sectionName] for the active request, ignoring a request that is not current. */
    fun beginPhase(
        requestId: Long,
        sectionName: String,
    ) {
        synchronized(lock) {
            if (activeRequest?.first != requestId || !sink.isEnabled()) return
            val key = PhaseKey(requestId, sectionName)
            if (key in activePhases) return
            val cookie = nextCookie()
            sink.beginAsyncSection(sectionName, cookie)
            activePhases[key] = cookie
        }
    }

    /** Closes the slice [sectionName] opened for [requestId], if it is still open. */
    fun endPhase(
        requestId: Long,
        sectionName: String,
    ) {
        synchronized(lock) {
            val cookie = activePhases.remove(PhaseKey(requestId, sectionName)) ?: return
            sink.endAsyncSection(sectionName, cookie)
        }
    }

    suspend fun <T> tracePhase(
        requestId: Long,
        sectionName: String,
        block: suspend () -> T,
    ): T {
        beginPhase(requestId, sectionName)
        return try {
            block()
        } finally {
            endPhase(requestId, sectionName)
        }
    }

    fun finishRequest(requestId: Long) {
        synchronized(lock) {
            val active = activeRequest?.takeIf { it.first == requestId } ?: return
            endRequestLocked(requestId, active.second)
        }
    }

    /** Closes every phase and the open total or lock-wait slice of [requestId], under the trace lock. */
    private fun endRequestLocked(
        requestId: Long,
        requestCookie: Int,
    ) {
        activePhases
            .filterKeys { it.requestId == requestId }
            .forEach { (key, cookie) -> sink.endAsyncSection(key.sectionName, cookie) }
        activePhases.keys.removeAll { it.requestId == requestId }
        val openSlice =
            if (lockWaiting) NotificationRouteTraceSection.APP_LOCK_WAIT else NotificationRouteTraceSection.TOTAL
        sink.endAsyncSection(openSlice, requestCookie)
        if (activeRequest?.first == requestId) activeRequest = null
        lockWaiting = false
    }

    private fun nextCookie(): Int =
        cookieCounter.updateAndGet { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
}
