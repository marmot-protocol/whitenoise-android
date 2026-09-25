package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.marmotkit.ProductEventFfi
import dev.ipf.marmotkit.ProductEventPropertyFfi
import dev.ipf.marmotkit.TimelinePageFfi

internal data class RecoveryStampedTimelineWindow(
    val page: TimelinePageFfi,
    val recoveryGeneration: Long?,
    val receivedAtElapsedMs: Long,
    val productObservationTicket: Long?,
)

/** Binds presentation observations to the app's consent-gated product recorder. */
internal fun WhiteNoiseAppState.conversationWindowPresentationTiming() =
    ConversationWindowPresentationTiming(
        nowMs = SystemClock::elapsedRealtime,
        emit = { ticket, observation ->
            recordProductEvent(observation.event(), ticket)
            observation.hostOperation()?.let { operation ->
                recordHostPerformance(
                    operation = operation,
                    durationMs = observation.elapsedMs,
                    outcome = observation.outcome.hostOutcome(),
                )
            }
        },
    )

/** Records the first shown conversation frame after its authoritative timeline is published. */
internal fun ConversationController.markWindowVisibleForPresentationTiming() = windowPresentationTiming.windowVisible()

/** Settles the newest live inbound-message timing only after Compose has produced a visible frame. */
internal fun ConversationController.markInboundMessageVisibleForHostPerformance() {
    inboundVisibleHostAttempt.success()
}

/** Records the first frame for which this conversation's composer is actually available. */
internal fun ConversationController.markComposerReadyForPresentationTiming() = windowPresentationTiming.composerReady()

/** Requires authoritative content and a settled visible route before timing the first frame. */
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
    /** Converts a duration into the finite schema accepted by the product recorder. */
    fun event(): ProductEventFfi =
        ProductEventFfi(
            name = stage.eventName,
            properties =
                listOf(
                    ProductEventPropertyFfi("elapsed", productDurationBucket(elapsedMs)),
                    ProductEventPropertyFfi("outcome", outcome.value),
                ),
        )

    /** Maps rendered milestones onto MDK's fixed host registry. */
    fun hostOperation(): HostPerformanceOperationFfi? =
        when (stage) {
            ConversationPresentationStage.WINDOW_VISIBLE -> HostPerformanceOperationFfi.CONVERSATION_LOCAL_VISIBLE
            ConversationPresentationStage.COMPOSER_READY -> HostPerformanceOperationFfi.CONVERSATION_COMPOSER_READY
            ConversationPresentationStage.TIMELINE_PUBLISHED -> null
        }
}

/** Converts the existing presentation terminal state without inventing success. */
private fun ConversationPresentationOutcome.hostOutcome(): HostPerformanceOutcomeFfi =
    when (this) {
        ConversationPresentationOutcome.SUCCESS -> HostPerformanceOutcomeFfi.SUCCESS
        ConversationPresentationOutcome.FAILURE -> HostPerformanceOutcomeFfi.FAILURE
        ConversationPresentationOutcome.CANCELLED -> HostPerformanceOutcomeFfi.CANCELLED
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
    private var windowObservedBeforePublication = false

    /** Captures the first native receipt and the consent ticket attached to that receipt. */
    @Synchronized
    fun begin(
        receivedAtElapsedMs: Long,
        ticket: Long?,
    ) {
        if (startedAtElapsedMs != null) return
        if (publicationSettled || windowSettled || composerSettled) return
        startedAtElapsedMs = receivedAtElapsedMs.coerceAtLeast(0L)
        this.ticket = ticket
        if (composerObservedBeforeReceipt) settleComposer(ConversationPresentationOutcome.SUCCESS)
    }

    /** Settles publication, then any visible frame observed before publication completed. */
    @Synchronized
    fun timelinePublished() {
        settlePublication(ConversationPresentationOutcome.SUCCESS)
        if (publicationSucceeded && windowObservedBeforePublication) {
            settleWindow(ConversationPresentationOutcome.SUCCESS)
        }
    }

    /** Retains an early frame until the authoritative timeline has published successfully. */
    @Synchronized
    fun windowVisible() {
        if (publicationSucceeded) {
            settleWindow(ConversationPresentationOutcome.SUCCESS)
        } else {
            windowObservedBeforePublication = true
        }
    }

    /** Records composer readiness even if the composer precedes the first native receipt. */
    @Synchronized
    fun composerReady() {
        if (startedAtElapsedMs == null) {
            composerObservedBeforeReceipt = true
        } else {
            settleComposer(ConversationPresentationOutcome.SUCCESS)
        }
    }

    /** Marks only outstanding stages as failed, preserving any earlier success. */
    @Synchronized
    fun fail() {
        settleOutstanding(ConversationPresentationOutcome.FAILURE)
    }

    /** Marks only outstanding stages as cancelled when the controller closes. */
    @Synchronized
    fun cancel() {
        settleOutstanding(ConversationPresentationOutcome.CANCELLED)
    }

    /** Applies one terminal outcome to each stage that has not already settled. */
    private fun settleOutstanding(outcome: ConversationPresentationOutcome) {
        settlePublication(outcome)
        settleWindow(outcome)
        settleComposer(outcome)
    }

    /** Records the first publication outcome and gates successful visibility on it. */
    private fun settlePublication(outcome: ConversationPresentationOutcome) {
        if (publicationSettled || startedAtElapsedMs == null) return
        publicationSettled = true
        publicationSucceeded = outcome == ConversationPresentationOutcome.SUCCESS
        emit(ConversationPresentationStage.TIMELINE_PUBLISHED, outcome)
    }

    /** Records the first visible-frame outcome exactly once. */
    private fun settleWindow(outcome: ConversationPresentationOutcome) {
        if (windowSettled || startedAtElapsedMs == null) return
        windowSettled = true
        emit(ConversationPresentationStage.WINDOW_VISIBLE, outcome)
    }

    /** Records the first composer outcome exactly once. */
    private fun settleComposer(outcome: ConversationPresentationOutcome) {
        if (composerSettled || startedAtElapsedMs == null) return
        composerSettled = true
        emit(ConversationPresentationStage.COMPOSER_READY, outcome)
    }

    /** Emits elapsed monotonic time relative to the captured native receipt. */
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
