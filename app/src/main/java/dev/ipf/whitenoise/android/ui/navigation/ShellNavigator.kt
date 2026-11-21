package dev.ipf.whitenoise.android.ui.navigation

import android.util.Log
import androidx.compose.runtime.mutableStateOf

/**
 * The future single route owner, running in shadow mode. It is process-retained through [MainShellProcessState], so
 * like the selected conversation it survives Activity recreation. Master's hand-written route fields still decide
 * everything the user sees: this navigator only receives the same intents as events, and [ShellShadowLog] compares
 * the two after every event and every committed shell composition.
 */
internal class ShellNavigator {
    private val stackState = mutableStateOf(CHATS_ROOT)

    /** The current route stack, read-only. Never empty, rooted at [ShellRoute.Chats]. */
    val stack: List<ShellRoute>
        get() = stackState.value

    /** Advances on every applied event, so a caller can tell whether its call path sent one. */
    var revision: Int = 0
        private set

    /** The name of the most recently applied event, for the shadow record. */
    var lastEventName: String = "none"
        private set

    /** Applies [event] through the pure reducer. */
    fun dispatch(event: ShellNavEvent) {
        stackState.value = reduceShellRoute(stackState.value, event)
        revision += 1
        lastEventName = event.eventName
    }
}

/** One shadow disagreement: where it was seen and both whole route values. */
internal data class ShellShadowMismatch(
    val origin: String,
    val navigator: List<ShellRoute>,
    val legacy: List<ShellRoute>,
)

/**
 * Process-wide record of shadow comparisons. A mismatch never throws in production: it is counted, kept (up to a
 * small cap) for tests to assert on, and logged at debug level by route shape only, with no account, group, message
 * id or text, because the shell does not log those today.
 */
internal object ShellShadowLog {
    private const val TAG = "ShellShadow"
    private const val MAX_RECORDED = 32
    private val lock = Any()
    private val recorded = ArrayList<ShellShadowMismatch>()
    private var comparisonCount = 0L
    private var mismatchTotal = 0L
    private val originTotals = LinkedHashMap<String, Long>()

    /** How many comparisons ran since the last [reset]. */
    val comparisons: Long
        get() = synchronized(lock) { comparisonCount }

    /** How many mismatches were seen since the last [reset], including any beyond the recorded cap. */
    val mismatchCount: Long
        get() = synchronized(lock) { mismatchTotal }

    /** How many comparisons each origin (an event name, or the per-composition check) drove since the last [reset]. */
    val originCounts: Map<String, Long>
        get() = synchronized(lock) { originTotals.toMap() }

    /** The first recorded mismatches since the last [reset]. */
    val mismatches: List<ShellShadowMismatch>
        get() = synchronized(lock) { recorded.toList() }

    /** Compares the whole route values, open context included, and records a disagreement. */
    fun compare(
        origin: String,
        navigator: List<ShellRoute>,
        legacy: List<ShellRoute>,
    ): Boolean {
        val matches = navigator == legacy
        synchronized(lock) {
            comparisonCount += 1
            originTotals[origin] = (originTotals[origin] ?: 0L) + 1L
            if (!matches) {
                mismatchTotal += 1
                if (recorded.size < MAX_RECORDED) recorded += ShellShadowMismatch(origin, navigator, legacy)
            }
        }
        if (!matches) {
            runCatching {
                Log.d(TAG, "mismatch origin=$origin navigator=${routeShape(navigator)} legacy=${routeShape(legacy)}")
            }
        }
        return matches
    }

    /** Forgets every comparison and mismatch, for a test that starts its own record. */
    fun reset() {
        synchronized(lock) {
            comparisonCount = 0L
            mismatchTotal = 0L
            originTotals.clear()
            recorded.clear()
        }
    }
}

/** A redacted outline of [stack] for logs: entry kinds and Settings details, never identifiers or message ids. */
internal fun routeShape(stack: List<ShellRoute>): String =
    stack.joinToString(" > ") { route ->
        when (route) {
            ShellRoute.Chats -> "Chats"
            ShellRoute.Diagnostics -> "Diagnostics"
            is ShellRoute.Settings -> "Settings(${route.detail?.name ?: "home"})"
            is ShellRoute.Conversation ->
                "Conversation(pinned=${route.open.pinnedAccountRef != null}, " +
                    "notification=${route.open.notificationOpenRequestId}, " +
                    "trace=${route.open.notificationRouteTraceRequestId}, " +
                    "focus=${route.open.focusMessageId != null}/${route.open.focusMessageRequestId})"
        }
    }
