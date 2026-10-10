package dev.ipf.whitenoise.android.benchmark

import kotlin.math.ceil

/**
 * The four lifecycle journeys of the notification-route contract (#586). Budgets are the issue's
 * externally measured limits for a tap on a message card; [measured] records whether this change shipped
 * a device run for the journey, so an unmeasured one cannot be mistaken for a passing one.
 */
internal enum class NotificationRouteJourney(
    val label: String,
    val p95BudgetMs: Long,
    val maxBudgetMs: Long,
    val measured: Boolean,
) {
    /** An existing task and runtime, restored to a different account before each sample. */
    WARM_TASK("warmTask", p95BudgetMs = 1_000L, maxBudgetMs = 1_500L, measured = true),

    /** The process is killed before each tap, so process creation is part of the route. */
    COLD_PROCESS("coldProcess", p95BudgetMs = 3_000L, maxBudgetMs = 5_000L, measured = false),

    /** A live process whose target-account runtime is suspended, so activation is part of the route. */
    RUNTIME_NOT_READY("runtimeNotReady", p95BudgetMs = 2_000L, maxBudgetMs = 3_000L, measured = false),

    /** App lock is active: the unlock surface and the post-unlock route have separate budgets. */
    APP_LOCK_DEFERRED("appLockDeferred", p95BudgetMs = 1_000L, maxBudgetMs = 1_500L, measured = false),
}

/** Post-unlock and combined active-processing budgets for [NotificationRouteJourney.APP_LOCK_DEFERRED]. */
internal object AppLockRouteBudgets {
    const val POST_UNLOCK_P95_MS = 1_500L
    const val POST_UNLOCK_MAX_MS = 2_500L
    const val COMBINED_P95_MS = 2_500L
    const val COMBINED_MAX_MS = 4_000L
}

/** Median, P95 and maximum of one journey's measured iterations, in milliseconds. */
internal data class RouteLatencyStats(
    val samples: Int,
    val medianMs: Long,
    val p95Ms: Long,
    val maxMs: Long,
) {
    /** One line for the log and the failure message, free of any fixture identifier. */
    fun report(): String = "samples=$samples median=${medianMs}ms p95=${p95Ms}ms max=${maxMs}ms"
}

/** Nearest-rank percentile over [durationsMs], which must not be empty. */
internal fun routeLatencyStats(durationsMs: List<Long>): RouteLatencyStats {
    require(durationsMs.isNotEmpty()) { "A journey needs at least one measured sample." }
    val sorted = durationsMs.sorted()

    /** Nearest-rank value at [fraction] of the sorted samples. */
    fun at(fraction: Double): Long = sorted[ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
    return RouteLatencyStats(sorted.size, at(MEDIAN_FRACTION), at(P95_FRACTION), sorted.last())
}

/**
 * Setup work is reported next to the measured set and never mixed into it: a failed or retried setup
 * is counted, and the iteration that follows it is still measured and still reported.
 */
internal class RouteSetupLedger {
    var attempts = 0
        private set
    var failures = 0
        private set
    var retries = 0
        private set
    var totalMs = 0L
        private set

    /** Runs one setup, retrying a failure once, and keeps the counts whatever happens. */
    fun run(setup: () -> Unit) {
        attempts += 1
        val startedAt = System.nanoTime()
        try {
            setup()
        } catch (failure: IllegalStateException) {
            failures += 1
            retries += 1
            attempts += 1
            setup()
        } finally {
            totalMs += (System.nanoTime() - startedAt) / NANOS_PER_MILLI
        }
    }

    /** One line for the log, separate from the measured journey's statistics. */
    fun report(): String = "setupAttempts=$attempts setupFailures=$failures setupRetries=$retries setupTotal=${totalMs}ms"

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Reads the app's own rendered-surface markers (`WNWarmResume`) so a loading or chat-list surface drawn
 * between the tap and the conversation is a failure, not just a slower sample. UiAutomator polling can
 * miss a surface that lives for one frame, which is why this reads what the root actually drew.
 */
internal object RenderedSurfaceLog {
    private val surfacePattern = Regex("event=rendered-surface-frame .*surface=([a-z]+)")

    /** Surfaces that must never draw between the tap and the first conversation frame. */
    private val intermediate = setOf("startuploading", "fullscreenloading", "chatlist")

    /** Every rendered surface in [logcat], in order. */
    fun surfaces(logcat: String): List<String> = logcat.lineSequence().mapNotNull { surfacePattern.find(it)?.groupValues?.get(1) }.toList()

    /** The intermediate surfaces drawn before the first conversation frame, empty when the route was direct. */
    fun intermediateBeforeConversation(logcat: String): List<String> = surfaces(logcat).takeWhile { it != "conversation" }.filter { it in intermediate }
}

private const val MEDIAN_FRACTION = 0.50
private const val P95_FRACTION = 0.95
