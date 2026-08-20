package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationInitialPresentationTest {
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

    @Test
    fun historyAndExplicitDestinationsWaitForTheirAuthoritativeAnchor() {
        assertFalse(reveal(unread = 1))
        assertFalse(reveal(hasRestore = true))
        assertFalse(reveal(hasFocus = true))
        assertFalse(reveal(notificationRequest = 1L))
    }

    @Test
    fun seededTailIndexTargetsTheBottomSpacer() {
        assertTrue(seededConversationTailListIndex(1) == 2)
        assertTrue(seededConversationTailListIndex(4) == 5)
    }

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

    @Test
    fun seededTailAnchorNeverWedgesTheRevealOnPersistentRefusal() =
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
        }

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
