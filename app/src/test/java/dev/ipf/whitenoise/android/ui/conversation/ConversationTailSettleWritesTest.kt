package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real settle transaction without pretending repeated no-op scrolls are free. */
class ConversationTailSettleWritesTest {
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

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index
        }
    }
}
