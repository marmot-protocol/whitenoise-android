package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationMentionNavigationTest {
    @Test
    fun measuredShortMentionUsesNegativeReadingStartOffset() =
        runTest {
            val writer = RecordingWriter()
            val reached =
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { 5 },
                    readLayout = { ConversationMentionJumpLayout(500, 80) },
                    awaitLayout = {},
                )
            assertTrue(reached)
            assertEquals(listOf(Write(true, 5, -420)), writer.writes)
        }

    @Test
    fun unmeasuredTallMentionCorrectsFreshHeightAndShiftedIndex() =
        runTest {
            val writer = RecordingWriter()
            var index = 5
            var measured = false
            val reached =
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { index },
                    readLayout = { ConversationMentionJumpLayout(500, if (measured) 800 else null) },
                    awaitLayout = {
                        index = 6
                        measured = true
                    },
                )
            assertTrue(reached)
            assertEquals(listOf(Write(true, 5, 0), Write(false, 6, 300)), writer.writes)
        }

    @Test
    fun cachedEstimateCannotReportSuccessWithoutLiveMeasurement() =
        runTest {
            val writer = RecordingWriter()
            val reached =
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { 5 },
                    readLayout = { ConversationMentionJumpLayout(500, null, estimatedItemHeightPx = 800) },
                    awaitLayout = {},
                )
            assertFalse(reached)
            assertEquals(listOf(Write(true, 5, 300)), writer.writes)
        }

    @Test
    fun missingTargetDoesNotWriteOrReportSuccess() =
        runTest {
            val writer = RecordingWriter()
            assertFalse(
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "missing",
                    resolveTargetIndex = { null },
                    readLayout = { error("no target geometry should be read") },
                    awaitLayout = {},
                ),
            )
            assertTrue(writer.writes.isEmpty())
        }

    @Test
    fun disappearingTargetAfterApproachDoesNotCorrectOrReportSuccess() =
        runTest {
            val writer = RecordingWriter()
            var index: Int? = 5
            val reached =
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { index },
                    readLayout = { ConversationMentionJumpLayout(500, null) },
                    awaitLayout = { index = null },
                )
            assertFalse(reached)
            assertEquals(listOf(Write(true, 5, 0)), writer.writes)
        }

    @Test
    fun farMentionUsesTheExistingBoundedApproach() =
        runTest {
            val writer = RecordingWriter()
            assertTrue(
                ConversationScrollCoordinator(writer).jumpToMentionReadingStart(
                    targetMessageId = "mention",
                    resolveTargetIndex = { 200 },
                    readLayout = { ConversationMentionJumpLayout(500, 800) },
                    awaitLayout = {},
                ),
            )
            assertEquals(listOf(Write(false, 190, 0), Write(true, 200, 300)), writer.writes)
        }

    @Test
    fun userDragCancelsThePendingMeasurementCorrection() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reached = true
            val jump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    reached =
                        coordinator.jumpToMentionReadingStart(
                            targetMessageId = "mention",
                            resolveTargetIndex = { 5 },
                            readLayout = { ConversationMentionJumpLayout(500, null) },
                            awaitLayout = {
                                waiting.complete(Unit)
                                release.await()
                            },
                        )
                }
            waiting.await()
            coordinator.onUserGestureStarted(ConversationScrollAnchor(5, 0, "msg:mention", "mention"))
            release.complete(Unit)
            jump.join()
            assertFalse(reached)
            assertEquals(listOf(Write(true, 5, 0)), writer.writes)
        }

    private data class Write(
        val animated: Boolean,
        val index: Int,
        val offset: Int,
    )

    private class RecordingWriter : ConversationScrollWriter {
        override var firstVisibleItemIndex = 0
        val writes = mutableListOf<Write>()

        override suspend fun scrollToItem(index: Int, scrollOffset: Int) {
            firstVisibleItemIndex = index
            writes += Write(false, index, scrollOffset)
        }

        override suspend fun animateScrollToItem(index: Int, scrollOffset: Int) {
            firstVisibleItemIndex = index
            writes += Write(true, index, scrollOffset)
        }
    }
}
