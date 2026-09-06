package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInitialPresentationTest {
    /** Allows a projected, fully-read conversation to seed its real tail immediately. */
    @Test
    fun ordinaryReadTailAnchorsOnTheFirstFrameEvenWithoutASeed() {
        assertTrue(
            shouldAnchorConversationTailOnFirstFrame(
                entryUnreadCount = 0,
                projectionAvailable = true,
                hasScrollRestore = false,
                hasFocusedDestination = false,
                notificationOpenRequestId = 0L,
            ),
        )
    }

    /** Keeps a direct open hidden while its unread boundary is still unknown. */
    @Test
    fun provisionalDirectOpenWaitsForUnreadProjectionBeforeTailAnchoring() {
        assertFalse(
            shouldAnchorConversationTailOnFirstFrame(
                entryUnreadCount = 0,
                projectionAvailable = false,
                hasScrollRestore = false,
                hasFocusedDestination = false,
                notificationOpenRequestId = 0L,
            ),
        )
    }

    /** Defers first-frame tail ownership to unread, restore, focus, and notification anchors. */
    @Test
    fun historyAndExplicitDestinationsWaitForTheirAuthoritativeAnchor() {
        assertFalse(reveal(unread = 1))
        assertFalse(reveal(hasRestore = true))
        assertFalse(reveal(hasFocus = true))
        assertFalse(reveal(notificationRequest = 1L))
    }

    /** Maps a seeded timeline to its real final lazy row after the single top spacer. */
    @Test
    fun seededTailIndexTargetsTheRealFinalRow() {
        assertEquals(0, seededConversationTailListIndex(0))
        assertEquals(1, seededConversationTailListIndex(1))
        assertEquals(4, seededConversationTailListIndex(4))
    }

    /** Keeps an oversized seeded row hidden from paint, TalkBack, and useful-frame telemetry until corrected. */
    @Test
    fun oversizedSeededTailVisibilityWaitsForPhysicalAlignment() {
        assertFalse(
            conversationTranscriptVisibilityCommitted(
                initialTimelineAnchored = true,
                anchorTailImmediately = true,
                seededTailAlignmentCommitted = false,
                viewportMeasured = true,
                canScrollForward = true,
            ),
        )
        assertFalse(
            conversationTranscriptVisibilityCommitted(
                initialTimelineAnchored = true,
                anchorTailImmediately = true,
                seededTailAlignmentCommitted = false,
                viewportMeasured = false,
                canScrollForward = false,
            ),
        )
        assertTrue(
            conversationTranscriptVisibilityCommitted(
                initialTimelineAnchored = true,
                anchorTailImmediately = true,
                seededTailAlignmentCommitted = false,
                viewportMeasured = true,
                canScrollForward = false,
            ),
        )
        assertTrue(
            conversationTranscriptVisibilityCommitted(
                initialTimelineAnchored = true,
                anchorTailImmediately = true,
                seededTailAlignmentCommitted = true,
                viewportMeasured = true,
                canScrollForward = false,
            ),
        )
    }

    /** Retries a transiently superseded seeded-tail write while follow intent is still active. */
    @Test
    fun seededTailAnchorRetriesASupersededFollowAcrossFrames() =
        runTest {
            var attempts = 0
            var frames = 0
            val positioned =
                reconcileSeededTailAnchor(
                    followTail = { ++attempts >= 3 },
                    isFollowingTail = { true },
                    awaitFrame = { frames++ },
                )
            assertTrue(positioned)
            assertEquals(3, attempts)
            assertEquals(2, frames)
        }

    /** Stops retrying when explicit navigation replaces the seeded tail-follow intent. */
    @Test
    fun seededTailAnchorYieldsToChangedNavigationIntent() =
        runTest {
            var attempts = 0
            val positioned =
                reconcileSeededTailAnchor(
                    followTail = {
                        attempts++
                        false
                    },
                    isFollowingTail = { false },
                    awaitFrame = { },
                )
            assertFalse(positioned)
            assertEquals(1, attempts)
        }

    /** Refuses reveal when every bounded attempt loses and tail pixels remain unread. */
    @Test
    fun refusedSeededTailEpochCannotCommitAnUnalignedReveal() =
        runTest {
            var attempts = 0
            val positioned =
                reconcileSeededTailAnchor(
                    followTail = {
                        attempts++
                        false
                    },
                    isFollowingTail = { true },
                    awaitFrame = { },
                )
            assertFalse(positioned)
            assertEquals(SEEDED_TAIL_ANCHOR_MAX_ATTEMPTS, attempts)
            assertFalse(
                seededTailAlignmentMayCommit(
                    positioned = positioned,
                    isFollowingTail = true,
                    canScrollForward = true,
                ),
            )
            assertTrue(
                seededTailAlignmentMayCommit(
                    positioned = positioned,
                    isFollowingTail = false,
                    canScrollForward = true,
                ),
            )
        }

    /** Retries immediately when the transient owner releases on the final refused frame. */
    @Test
    fun finalRefusedFrameOwnerReleaseCannotLoseTheTailRetryWakeup() =
        runTest {
            var attempts = 0
            var frames = 0
            var ownerReleased = false

            val alignmentMayCommit =
                awaitSeededTailAlignmentUntilCommit(
                    followTail = {
                        attempts++
                        ownerReleased
                    },
                    isFollowingTail = { true },
                    canScrollForward = { true },
                    awaitFrame = {
                        frames++
                        if (frames == SEEDED_TAIL_ANCHOR_MAX_ATTEMPTS) {
                            ownerReleased = true
                        }
                    },
                )

            assertTrue(alignmentMayCommit)
            assertEquals(SEEDED_TAIL_ANCHOR_MAX_ATTEMPTS + 1, attempts)
            assertEquals(SEEDED_TAIL_ANCHOR_MAX_ATTEMPTS, frames)
        }

    /** Exhausts one aggregate budget instead of restarting batches under persistent supersession. */
    @Test
    fun persistentTailSupersessionStopsAtTheAggregateRecoveryBudget() =
        runTest {
            var attempts = 0
            var frames = 0

            val alignmentMayCommit =
                awaitSeededTailAlignmentUntilCommit(
                    followTail = {
                        attempts++
                        false
                    },
                    isFollowingTail = { true },
                    canScrollForward = { true },
                    awaitFrame = { frames++ },
                )

            assertFalse(alignmentMayCommit)
            assertEquals(SEEDED_TAIL_ALIGNMENT_MAX_ATTEMPTS, attempts)
            assertEquals(SEEDED_TAIL_ALIGNMENT_MAX_ATTEMPTS, frames)
        }

    /** Lets newer history ownership dismiss visible recovery without another tail attempt. */
    @Test
    fun newerHistoryIntentSafelyDismissesExhaustedTailRecovery() =
        runTest {
            var followingTail = true
            var safeStateWaits = 0

            awaitSeededTailAlignmentSafeFallback(
                isFollowingTail = { followingTail },
                canScrollForward = { true },
                awaitSafeState = { safeToReveal ->
                    safeStateWaits++
                    assertFalse(safeToReveal())
                    followingTail = false
                    assertTrue(safeToReveal())
                },
            )

            assertEquals(1, safeStateWaits)
        }

    /** Evaluates whether a first-frame path may own the tail under one supplied routing constraint. */
    private fun reveal(
        unread: Int = 0,
        hasRestore: Boolean = false,
        hasFocus: Boolean = false,
        notificationRequest: Long = 0L,
    ) = shouldAnchorConversationTailOnFirstFrame(
        entryUnreadCount = unread,
        projectionAvailable = true,
        hasScrollRestore = hasRestore,
        hasFocusedDestination = hasFocus,
        notificationOpenRequestId = notificationRequest,
    )
}
