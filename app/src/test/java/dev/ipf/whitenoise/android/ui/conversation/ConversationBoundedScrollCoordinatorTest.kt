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
class ConversationBoundedScrollCoordinatorTest {
    @Test
    fun farForwardJumpSnapsNearTheTargetBeforeAnimatingTheBoundedRemainder() =
        runTest {
            val writer = RecordingScrollWriter(initialIndex = 10)
            val coordinator = ConversationScrollCoordinator(writer)

            coordinator.programmaticJump(
                targetMessageId = "target",
                reason = ConversationScrollReason.Search,
            ) {
                animateScrollToItem(200, 36)
            }

            assertEquals(
                listOf(
                    ScrollWrite.Snap(190, 0),
                    ScrollWrite.Animate(200, 36),
                ),
                writer.writes,
            )
        }

    @Test
    fun farJumpReResolvesItsMessageBackedTargetAfterEveryPrePositionSnap() =
        runTest {
            var resolvedTargetIndex = 200
            var snapCount = 0
            val writer =
                RecordingScrollWriter(
                    initialIndex = 10,
                    onSnap = {
                        snapCount++
                        if (snapCount <= 2) resolvedTargetIndex++
                    },
                )
            val coordinator = ConversationScrollCoordinator(writer)

            coordinator.programmaticJump(
                targetMessageId = "target",
                reason = ConversationScrollReason.Search,
            ) {
                animateScrollToItem(200, 36) { resolvedTargetIndex }
            }

            assertEquals(
                listOf(
                    ScrollWrite.Snap(190, 0),
                    ScrollWrite.Snap(191, 0),
                    ScrollWrite.Snap(192, 0),
                    ScrollWrite.Animate(202, 36),
                ),
                writer.writes,
            )
        }

    @Test
    fun repeatedTargetMovementUsesOnlyBoundedRepositionAttempts() =
        runTest {
            var resolvedTargetIndex = 200
            var prePositionSnapCount = 0
            val writer =
                RecordingScrollWriter(
                    initialIndex = 10,
                    onSnap = { offset ->
                        if (offset == 0 && prePositionSnapCount++ < 4) resolvedTargetIndex++
                    },
                )
            val coordinator = ConversationScrollCoordinator(writer)

            val completed =
                coordinator.programmaticJump("target", ConversationScrollReason.Search) {
                    animateScrollToItem(200, 36) { resolvedTargetIndex }
                }

            assertTrue(completed)
            assertEquals(
                listOf(
                    ScrollWrite.Snap(190, 0),
                    ScrollWrite.Snap(191, 0),
                    ScrollWrite.Snap(192, 0),
                    ScrollWrite.Snap(203, 36),
                ),
                writer.writes,
            )
        }

    @Test
    fun nearForwardJumpAnimatesDirectly() =
        runTest {
            val writer = RecordingScrollWriter(initialIndex = 100)
            val coordinator = ConversationScrollCoordinator(writer)

            coordinator.programmaticJump("target", ConversationScrollReason.Reply) {
                animateScrollToItem(110, 24)
            }

            assertEquals(listOf(ScrollWrite.Animate(110, 24)), writer.writes)
        }

    @Test
    fun nearBackwardJumpAnimatesDirectly() =
        runTest {
            val writer = RecordingScrollWriter(initialIndex = 100)
            val coordinator = ConversationScrollCoordinator(writer)

            coordinator.programmaticJump("target", ConversationScrollReason.Mention) {
                animateScrollToItem(90, 24)
            }

            assertEquals(listOf(ScrollWrite.Animate(90, 24)), writer.writes)
        }

    @Test
    fun farBackwardJumpSnapsNearTheTargetBeforeAnimatingTheBoundedRemainder() =
        runTest {
            val writer = RecordingScrollWriter(initialIndex = 300)
            val coordinator = ConversationScrollCoordinator(writer)

            coordinator.programmaticJump("target", ConversationScrollReason.JumpToNewest) {
                animateScrollToItem(100, 12)
            }

            assertEquals(
                listOf(
                    ScrollWrite.Snap(110, 0),
                    ScrollWrite.Animate(100, 12),
                ),
                writer.writes,
            )
        }

    @Test
    fun userGestureBetweenFarJumpPhasesCancelsTheFinalAnimation() =
        runTest {
            val writer = PausingFirstSnapScrollWriter(initialIndex = 10)
            val coordinator = ConversationScrollCoordinator(writer)
            var completed = true
            val jump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    completed =
                        coordinator.programmaticJump("target", ConversationScrollReason.Search) {
                            animateScrollToItem(200, 36)
                        }
                }
            writer.firstSnapStarted.await()

            coordinator.onUserGestureStarted(anchor(messageId = "reader", listIndex = 40, pixelOffset = 5))
            writer.releaseFirstSnap.complete(Unit)
            jump.join()

            assertFalse(completed)
            assertEquals(listOf(ScrollWrite.Snap(190, 0)), writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 5), coordinator.mode)
        }

    @Test
    fun newerJumpBetweenFarJumpPhasesIsTheOnlyCommandThatAnimatesToCompletion() =
        runTest {
            val writer = PausingFirstSnapScrollWriter(initialIndex = 10)
            val coordinator = ConversationScrollCoordinator(writer)
            var firstCompleted = true
            val firstJump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    firstCompleted =
                        coordinator.programmaticJump("old-target", ConversationScrollReason.Search) {
                            animateScrollToItem(200, 36)
                        }
                }
            writer.firstSnapStarted.await()

            val replacementCompleted =
                coordinator.programmaticJump("new-target", ConversationScrollReason.Reply) {
                    animateScrollToItem(20, 8)
                }
            writer.releaseFirstSnap.complete(Unit)
            firstJump.join()

            assertFalse(firstCompleted)
            assertTrue(replacementCompleted)
            assertEquals(
                listOf(
                    ScrollWrite.Snap(190, 0),
                    ScrollWrite.Snap(30, 0),
                    ScrollWrite.Animate(20, 8),
                ),
                writer.writes,
            )
        }

    private fun anchor(
        messageId: String? = null,
        itemId: String? = messageId?.let { "msg:$it" },
        listIndex: Int = 1,
        pixelOffset: Int = 0,
    ) = ConversationScrollAnchor(
        messageId = messageId,
        itemId = itemId,
        listIndex = listIndex,
        pixelOffset = pixelOffset,
    )

    private sealed interface ScrollWrite {
        data class Snap(
            val index: Int,
            val offset: Int,
        ) : ScrollWrite

        data class Animate(
            val index: Int,
            val offset: Int,
        ) : ScrollWrite
    }

    private class PausingFirstSnapScrollWriter(
        initialIndex: Int,
    ) : ConversationScrollWriter {
        val writes = mutableListOf<ScrollWrite>()
        val firstSnapStarted = CompletableDeferred<Unit>()
        val releaseFirstSnap = CompletableDeferred<Unit>()
        override var firstVisibleItemIndex = initialIndex
        private var pauseNextSnap = true

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Snap(index, scrollOffset)
            firstVisibleItemIndex = index
            if (pauseNextSnap) {
                pauseNextSnap = false
                firstSnapStarted.complete(Unit)
                releaseFirstSnap.await()
            }
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Animate(index, scrollOffset)
            firstVisibleItemIndex = index
        }
    }

    private class RecordingScrollWriter(
        initialIndex: Int,
        private val onSnap: (Int) -> Unit = {},
    ) : ConversationScrollWriter {
        val writes = mutableListOf<ScrollWrite>()
        override var firstVisibleItemIndex = initialIndex

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Snap(index, scrollOffset)
            firstVisibleItemIndex = index
            onSnap(scrollOffset)
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Animate(index, scrollOffset)
            firstVisibleItemIndex = index
        }
    }
}
