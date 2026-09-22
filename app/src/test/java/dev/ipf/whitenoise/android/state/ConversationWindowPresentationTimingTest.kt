package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWindowPresentationTimingTest {
    @Test
    fun emitsInitialVisibilityAndComposerReadyOnceFromNativeReceipt() {
        var nowMs = 120L
        val emitted = mutableListOf<Pair<Long?, ConversationPresentationObservation>>()
        val timing =
            ConversationWindowPresentationTiming(
                nowMs = { nowMs },
                emit = { ticket, observation -> emitted += ticket to observation },
            )

        timing.begin(receivedAtElapsedMs = 100L, ticket = 7L)
        timing.timelinePublished()
        nowMs = 180L
        timing.composerReady()
        timing.timelinePublished()
        timing.composerReady()

        assertEquals(
            listOf(
                7L to
                    ConversationPresentationObservation(
                        ConversationPresentationStage.WINDOW_VISIBLE,
                        elapsedMs = 20L,
                        outcome = ConversationPresentationOutcome.SUCCESS,
                    ),
                7L to
                    ConversationPresentationObservation(
                        ConversationPresentationStage.COMPOSER_READY,
                        elapsedMs = 80L,
                        outcome = ConversationPresentationOutcome.SUCCESS,
                    ),
            ),
            emitted,
        )
    }

    @Test
    fun clearCancelsOnlyMilestonesStillOutstanding() {
        var nowMs = 50L
        val emitted = mutableListOf<ConversationPresentationObservation>()
        val timing =
            ConversationWindowPresentationTiming(
                nowMs = { nowMs },
                emit = { _, observation -> emitted += observation },
            )

        timing.begin(receivedAtElapsedMs = 40L, ticket = null)
        timing.timelinePublished()
        nowMs = 75L
        timing.cancel()
        timing.cancel()

        assertEquals(
            listOf(
                ConversationPresentationObservation(
                    ConversationPresentationStage.WINDOW_VISIBLE,
                    elapsedMs = 10L,
                    outcome = ConversationPresentationOutcome.SUCCESS,
                ),
                ConversationPresentationObservation(
                    ConversationPresentationStage.COMPOSER_READY,
                    elapsedMs = 35L,
                    outcome = ConversationPresentationOutcome.CANCELLED,
                ),
            ),
            emitted,
        )
    }

    @Test
    fun failureSettlesBothMilestonesAndDurationUsesClosedBuckets() {
        var nowMs = 20L
        val emitted = mutableListOf<ConversationPresentationObservation>()
        val timing =
            ConversationWindowPresentationTiming(
                nowMs = { nowMs },
                emit = { _, observation -> emitted += observation },
            )

        timing.begin(receivedAtElapsedMs = 10L, ticket = 3L)
        nowMs = 36L
        timing.fail()

        assertEquals(2, emitted.size)
        assertTrue(emitted.all { it.elapsedMs == 26L && it.outcome == ConversationPresentationOutcome.FAILURE })
        assertEquals("le_10ms", productDurationBucket(10L))
        assertEquals("le_25ms", productDurationBucket(11L))
        assertEquals("le_60m", productDurationBucket(3_600_000L))
        assertEquals("gt_60m", productDurationBucket(3_600_001L))
        assertTrue(
            androidProductRegistry
                .filter { it.name.startsWith("app_conversation_") }
                .map { it.name }
                .containsAll(listOf("app_conversation_window_visible", "app_conversation_composer_ready")),
        )
    }
}
