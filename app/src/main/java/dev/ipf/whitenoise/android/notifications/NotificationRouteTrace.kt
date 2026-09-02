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
    private val cookieCounter = AtomicInteger()
    private var activeRequest: Pair<Long, Int>? = null
    private val activePhases = mutableMapOf<PhaseKey, Int>()

    fun startRequest(requestId: Long) {
        synchronized(lock) {
            activeRequest?.let { (activeRequestId, cookie) ->
                endRequestLocked(activeRequestId, cookie)
            }
            if (!Trace.isEnabled()) {
                activeRequest = null
                return
            }
            val cookie = nextCookie()
            Trace.beginAsyncSection(NotificationRouteTraceSection.TOTAL, cookie)
            activeRequest = requestId to cookie
        }
    }

    fun beginPhase(
        requestId: Long,
        sectionName: String,
    ) {
        synchronized(lock) {
            if (activeRequest?.first != requestId || !Trace.isEnabled()) return
            val key = PhaseKey(requestId, sectionName)
            if (key in activePhases) return
            val cookie = nextCookie()
            Trace.beginAsyncSection(sectionName, cookie)
            activePhases[key] = cookie
        }
    }

    fun endPhase(
        requestId: Long,
        sectionName: String,
    ) {
        synchronized(lock) {
            val cookie = activePhases.remove(PhaseKey(requestId, sectionName)) ?: return
            Trace.endAsyncSection(sectionName, cookie)
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

    private fun endRequestLocked(
        requestId: Long,
        requestCookie: Int,
    ) {
        activePhases
            .filterKeys { it.requestId == requestId }
            .forEach { (key, cookie) -> Trace.endAsyncSection(key.sectionName, cookie) }
        activePhases.keys.removeAll { it.requestId == requestId }
        Trace.endAsyncSection(NotificationRouteTraceSection.TOTAL, requestCookie)
        if (activeRequest?.first == requestId) activeRequest = null
    }

    private fun nextCookie(): Int =
        cookieCounter.updateAndGet { current ->
            if (current == Int.MAX_VALUE) 1 else current + 1
        }
}
