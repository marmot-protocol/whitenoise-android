package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.ProductEventFfi
import dev.ipf.marmotkit.ProductEventPropertyFfi
import dev.ipf.marmotkit.TimelinePageFfi

internal data class RecoveryStampedTimelineWindow(
    val page: TimelinePageFfi,
    val recoveryGeneration: Long?,
    val receivedAtElapsedMs: Long,
    val productObservationTicket: Long?,
)

internal fun WhiteNoiseAppState.conversationWindowPresentationTiming() =
    ConversationWindowPresentationTiming(
        nowMs = SystemClock::elapsedRealtime,
        emit = { ticket, observation -> recordProductEvent(observation.event(), ticket) },
    )

/** Records the first shown conversation frame after its authoritative timeline is published. */
internal fun ConversationController.markWindowVisibleForPresentationTiming() = windowPresentationTiming.windowVisible()

/** Records the first frame for which this conversation's composer is actually available. */
internal fun ConversationController.markComposerReadyForPresentationTiming() = windowPresentationTiming.composerReady()

internal fun conversationWindowCanReportVisible(
    timelinePublished: Boolean,
    transcriptReadyToReveal: Boolean,
    routeTransitionInProgress: Boolean,
    showingDetails: Boolean,
): Boolean = timelinePublished && transcriptReadyToReveal && !routeTransitionInProgress && !showingDetails

/** Captures consent at native receipt so a later permission change cannot admit old timing. */
internal fun WhiteNoiseAppState.productObservationTicket(): Long? = diagnostics.observations.ticket()

internal enum class ConversationPresentationStage(
    val eventName: String,
) {
    TIMELINE_PUBLISHED("app_conversation_timeline_published"),
    WINDOW_VISIBLE("app_conversation_window_visible"),
    COMPOSER_READY("app_conversation_composer_ready"),
}

internal enum class ConversationPresentationOutcome(
    val value: String,
) {
    SUCCESS("success"),
    FAILURE("failure"),
    CANCELLED("cancelled"),
}

/** One privacy-safe milestone measured from the first native window receipt for this controller. */
internal data class ConversationPresentationObservation(
    val stage: ConversationPresentationStage,
    val elapsedMs: Long,
    val outcome: ConversationPresentationOutcome,
) {
    fun event(): ProductEventFfi =
        ProductEventFfi(
            name = stage.eventName,
            properties =
                listOf(
                    ProductEventPropertyFfi("elapsed", productDurationBucket(elapsedMs)),
                    ProductEventPropertyFfi("outcome", outcome.value),
                ),
        )
}

/** Maps raw monotonic milliseconds onto MDK's fixed, bounded duration vocabulary. */
internal fun productDurationBucket(elapsedMs: Long): String {
    val duration = elapsedMs.coerceAtLeast(0L)
    val index = PRODUCT_DURATION_BOUNDS_MS.indexOfFirst { duration <= it }
    return if (index >= 0) PRODUCT_DURATION_BUCKETS[index] else PRODUCT_DURATION_BUCKETS.last()
}

/**
 * Exactly-once lifecycle for initial conversation presentation timing.
 *
 * The timer starts at the first native window receipt. A controller clear or load failure settles
 * only milestones that have not already succeeded, preventing duplicate outcome samples.
 */
internal class ConversationWindowPresentationTiming(
    private val nowMs: () -> Long,
    private val emit: (Long?, ConversationPresentationObservation) -> Unit,
) {
    private var startedAtElapsedMs: Long? = null
    private var ticket: Long? = null
    private var publicationSettled = false
    private var publicationSucceeded = false
    private var windowSettled = false
    private var composerSettled = false
    private var composerObservedBeforeReceipt = false

    @Synchronized
    fun begin(
        receivedAtElapsedMs: Long,
        ticket: Long?,
    ) {
        if (startedAtElapsedMs != null || publicationSettled || windowSettled || composerSettled) return
        startedAtElapsedMs = receivedAtElapsedMs.coerceAtLeast(0L)
        this.ticket = ticket
        if (composerObservedBeforeReceipt) settleComposer(ConversationPresentationOutcome.SUCCESS)
    }

    @Synchronized
    fun timelinePublished() {
        settlePublication(ConversationPresentationOutcome.SUCCESS)
    }

    @Synchronized
    fun windowVisible() {
        if (publicationSucceeded) settleWindow(ConversationPresentationOutcome.SUCCESS)
    }

    @Synchronized
    fun composerReady() {
        if (startedAtElapsedMs == null) {
            composerObservedBeforeReceipt = true
        } else {
            settleComposer(ConversationPresentationOutcome.SUCCESS)
        }
    }

    @Synchronized
    fun fail() {
        settleOutstanding(ConversationPresentationOutcome.FAILURE)
    }

    @Synchronized
    fun cancel() {
        settleOutstanding(ConversationPresentationOutcome.CANCELLED)
    }

    private fun settleOutstanding(outcome: ConversationPresentationOutcome) {
        settlePublication(outcome)
        settleWindow(outcome)
        settleComposer(outcome)
    }

    private fun settlePublication(outcome: ConversationPresentationOutcome) {
        if (publicationSettled || startedAtElapsedMs == null) return
        publicationSettled = true
        publicationSucceeded = outcome == ConversationPresentationOutcome.SUCCESS
        emit(ConversationPresentationStage.TIMELINE_PUBLISHED, outcome)
    }

    private fun settleWindow(outcome: ConversationPresentationOutcome) {
        if (windowSettled || startedAtElapsedMs == null) return
        windowSettled = true
        emit(ConversationPresentationStage.WINDOW_VISIBLE, outcome)
    }

    private fun settleComposer(outcome: ConversationPresentationOutcome) {
        if (composerSettled || startedAtElapsedMs == null) return
        composerSettled = true
        emit(ConversationPresentationStage.COMPOSER_READY, outcome)
    }

    private fun emit(
        stage: ConversationPresentationStage,
        outcome: ConversationPresentationOutcome,
    ) {
        val startedAt = checkNotNull(startedAtElapsedMs)
        emit(
            ticket,
            ConversationPresentationObservation(
                stage = stage,
                elapsedMs = (nowMs() - startedAt).coerceAtLeast(0L),
                outcome = outcome,
            ),
        )
    }
}

private val PRODUCT_DURATION_BOUNDS_MS =
    longArrayOf(
        10L,
        25L,
        50L,
        100L,
        250L,
        500L,
        1_000L,
        2_000L,
        5_000L,
        10_000L,
        30_000L,
        60_000L,
        300_000L,
        900_000L,
        3_600_000L,
    )

private val PRODUCT_DURATION_BUCKETS =
    arrayOf(
        "le_10ms",
        "le_25ms",
        "le_50ms",
        "le_100ms",
        "le_250ms",
        "le_500ms",
        "le_1s",
        "le_2s",
        "le_5s",
        "le_10s",
        "le_30s",
        "le_1m",
        "le_5m",
        "le_15m",
        "le_60m",
        "gt_60m",
    )
