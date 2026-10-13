package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A native clamp must be measured, fully readable and saved using its real coordinate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class ConversationNotificationClampTest {
    @Test
    fun newestNotificationSavesThePhysicalClampWithoutAllocatingReserve() =
        runTest {
            val writer = ClampWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val result =
                coordinator.commitInitialReadingStartAnchor(
                    targetMessageId = "target",
                    resultingMode = ConversationScrollMode.ReadingHistory("target", 0),
                    probe = naturalProbe(0, measured(offset = 0)),
                )
            assertTrue(result.reached)
            assertEquals(ConversationReadingStartPlacement(0, 0), result.placement)
            assertEquals(listOf(0 to -332), writer.writes)
            assertNull(coordinator.mentionReadingRowHeightPx)
        }

    @Test
    fun nearTailNotificationSavesItsPhysicalOffsetInsteadOfTheRequestedOvershoot() =
        runTest {
            val writer = ClampWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val result =
                coordinator.commitInitialReadingStartAnchor(
                    targetMessageId = "target",
                    resultingMode = ConversationScrollMode.ReadingHistory("target", 0),
                    probe = naturalProbe(1, measured(offset = 74)),
                )
            assertTrue(result.reached)
            assertEquals(ConversationReadingStartPlacement(1, -74), result.placement)
            assertNull(coordinator.mentionReadingRowHeightPx)
        }

    @Test
    fun aNaturalTailFlagNeverAcceptsClippedOccludedOrUnmeasuredTargets() {
        assertFalse(measured(offset = -20).isFullyVisibleAtNaturalTail)
        assertFalse(measured(offset = 0).copy(viewportStartOffsetPx = 24).isFullyVisibleAtNaturalTail)
        assertFalse(measured(offset = 380).isFullyVisibleAtNaturalTail)
        assertFalse(measured(offset = 0).copy(itemHeightPx = null).isFullyVisibleAtNaturalTail)
        assertFalse(measured(offset = 0).copy(itemOffsetPx = null).isFullyVisibleAtNaturalTail)
        assertFalse(measured(offset = 0).copy(isAtNaturalTail = false).isFullyVisibleAtNaturalTail)
    }

    private fun measured(offset: Int) =
        ConversationMentionJumpLayout(
            viewportEndOffsetPx = 412,
            itemHeightPx = 80,
            itemOffsetPx = offset,
            viewportStartOffsetPx = -8,
            isAtNaturalTail = true,
        )

    private fun naturalProbe(
        index: Int,
        layout: ConversationMentionJumpLayout,
    ) = ConversationReadingStartProbe(
        resolveTargetIndex = { index },
        readLayout = { layout },
        awaitLayout = {},
        traceSections = false,
        reserveTailSpace = false,
    )

    private class ClampWriter : ConversationScrollWriter {
        override val firstVisibleItemIndex = 0
        val writes = mutableListOf<Pair<Int, Int>>()

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index to scrollOffset
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            error("A hidden notification landing must never animate")
        }
    }
}
