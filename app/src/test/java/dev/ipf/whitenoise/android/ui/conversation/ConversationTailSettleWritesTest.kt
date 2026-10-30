package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real settle transaction without pretending repeated no-op scrolls are free. */
class ConversationTailSettleWritesTest {
    /**
     * A measured tail that never changes runs every settle frame yet writes only the initial resets, not one scroll per
     * frame.
     */
    @Test
    fun unchangedMeasuredTailKeepsInitialResetsWithoutPerFrameWrites() =
        runTest {
            val writer = TailWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var frames = 0
            assertTrue(
                coordinator.settleTailAfterLayoutChange(
                    resolveTailIndex = { 0 },
                    captureLayout = { readyLayout() },
                    maxSettleFrames = 24,
                    awaitFrame = { frames++ },
                ),
            )
            assertEquals(24, frames)
            assertEquals(listOf(0, 0), writer.writes)
        }

    /** A tail without a measured key identity cannot be proven stable, so every frame keeps its correction write. */
    @Test
    fun missingIdentityPreservesEveryCorrection() =
        runTest {
            val writer = TailWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            coordinator.settleTailAfterLayoutChange(
                resolveTailIndex = { 0 },
                captureLayout = { readyLayout().copy(measuredTailKey = null) },
                maxSettleFrames = 5,
                awaitFrame = {},
            )
            assertEquals(6, writer.writes.size)
        }

    /**
     * Replacing the tail row with identical geometry but a new key still resets its key anchor with a fresh correction.
     */
    @Test
    fun replacementWithIdenticalGeometryStillResetsItsKeyAnchor() =
        runTest {
            val writer = TailWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var frame = 0
            coordinator.settleTailAfterLayoutChange(
                resolveTailIndex = { 0 },
                captureLayout = { readyLayout().copy(measuredTailKey = if (frame < 4) "old" else "new") },
                maxSettleFrames = 6,
                awaitFrame = { frame++ },
            )
            assertEquals(3, writer.writes.size)
        }

    /** A tail index that changes mid-settle still receives a correction write at the new index. */
    @Test
    fun changedTailIndexStillReceivesACorrection() =
        runTest {
            val writer = TailWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var frame = 0
            coordinator.settleTailAfterLayoutChange(
                resolveTailIndex = { if (frame < 4) 0 else 1 },
                captureLayout = { readyLayout() },
                maxSettleFrames = 6,
                awaitFrame = { frame++ },
            )
            assertEquals(listOf(0, 0, 1), writer.writes)
        }

    /**
     * A delayed composer change and a covered tail keep their corrections and hold the settle until the tail is clear.
     */
    @Test
    fun delayedComposerChangeAndCoveredTailKeepCorrectionsAndSettlementWait() =
        runTest {
            val writer = TailWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var frame = 0
            coordinator.settleTailAfterLayoutChange(
                resolveTailIndex = { 0 },
                captureLayout = {
                    when {
                        frame < 4 -> readyLayout()
                        frame < 7 -> readyLayout().copy(tailOffsetPx = -8, viewportEndOffsetPx = 432)
                        else -> readyLayout().copy(viewportEndOffsetPx = 432)
                    }
                },
                settleTrigger = ConversationTailSettleTrigger.BottomGeometry,
                requireTailClearance = true,
                minimumSettleFrames = 8,
                maxSettleFrames = 24,
                awaitFrame = { frame++ },
            )
            assertEquals(9, frame)
            assertEquals(6, writer.writes.size)
        }

    /** A user gesture during an otherwise redundant settle loop still cancels it and ends tail following. */
    @Test
    fun userGestureStillCancelsAnOtherwiseRedundantSettleLoop() =
        runTest {
            val writer = TailWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var frame = 0
            val settled =
                coordinator.settleTailAfterLayoutChange(
                    resolveTailIndex = { 0 },
                    captureLayout = { readyLayout() },
                    awaitFrame = {
                        frame++
                        if (frame == 3) {
                            coordinator.onUserGestureStarted(
                                ConversationScrollAnchor(0, 0, "tail", "tail"),
                            )
                        }
                    },
                )
            assertFalse(settled)
            assertFalse(coordinator.isFollowingTail)
            assertEquals(listOf(0, 0), writer.writes)
        }

    /** Builds a ready tail layout with a single measured 48 px row and a stable tail key. */
    private fun readyLayout() =
        ConversationTailLayout(
            lastRowHeightPx = 48,
            tailOffsetPx = 0,
            tailSizePx = 48,
            viewportEndOffsetPx = 424,
            measuredTailKey = "tail",
        )

    private class TailWriter : ConversationScrollWriter {
        override val firstVisibleItemIndex = 0
        val writes = mutableListOf<Int>()

        /** Records the target index of an instant scroll. */
        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index
        }

        /** Records the target index of an animated scroll the same way as an instant one. */
        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index
        }
    }
}
