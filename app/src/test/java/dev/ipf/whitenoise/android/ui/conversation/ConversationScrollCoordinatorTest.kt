package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationScrollCoordinatorTest {
    @Test
    fun readingHistoryResumeRestoresLogicalAnchorInsteadOfFollowingTransientBottomGeometry() =
        runTest {
            val writer = RecordingScrollWriter()
            val anchor = anchor(messageId = "reader", listIndex = 18, pixelOffset = 72)
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 72),
                )
            val paused = coordinator.bookmark(anchor)

            coordinator.restoreViewport(
                snapshot = paused,
                resolveAnchorIndex = { 31 },
                tailIndex = 99,
                frameCount = 1,
                awaitFrame = {},
            )

            assertEquals(listOf(ScrollWrite.Snap(31, 72)), writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 72), coordinator.mode)
            assertFalse(coordinator.isFollowingTail)
        }

    @Test
    fun transitionSnapshotUsesTheDurableHistoryAnchorInsteadOfTransientGeometry() {
        val writer = RecordingScrollWriter()
        val coordinator =
            ConversationScrollCoordinator(
                writer = writer,
                initialMode = ConversationScrollMode.ReadingHistory("reader", 72),
            )
        val durable = anchor(messageId = "reader", listIndex = 18, pixelOffset = 72)
        coordinator.settleReadingAt(durable)

        val snapshot =
            coordinator.bookmark(
                anchor(messageId = "transient", listIndex = 42, pixelOffset = 0),
            )

        assertEquals(durable, snapshot.anchor)
        assertEquals(ConversationScrollMode.ReadingHistory("reader", 72), snapshot.settledMode)
    }

    @Test
    fun followingTailResumeKeepsTailAnchoredAcrossViewportFrames() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val paused = coordinator.bookmark(anchor(messageId = "last", listIndex = 40))

            coordinator.restoreViewport(
                snapshot = paused,
                resolveAnchorIndex = { 40 },
                tailIndex = 42,
                frameCount = 3,
                awaitFrame = {},
            )

            assertEquals(
                listOf(
                    ScrollWrite.Snap(42, 0),
                    ScrollWrite.Snap(42, 0),
                    ScrollWrite.Snap(42, 0),
                ),
                writer.writes,
            )
            assertTrue(coordinator.isFollowingTail)
        }

    @Test
    fun userGestureCancelsAnInFlightViewportChase() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val frames = Channel<Unit>(Channel.UNLIMITED)
            val started = CompletableDeferred<Unit>()
            val chase =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.followTailIfAllowed(
                        tailIndex = 50,
                        reason = ConversationScrollReason.ImeTransition,
                        frameCount = 24,
                        awaitFrame = {
                            started.complete(Unit)
                            frames.receive()
                        },
                    )
                }
            started.await()
            frames.send(Unit)
            runCurrent()
            assertEquals(listOf(ScrollWrite.Snap(50, 0)), writer.writes)

            coordinator.onUserGestureStarted(anchor(messageId = "reader", listIndex = 10, pixelOffset = 24))
            repeat(24) { frames.trySend(Unit) }
            runCurrent()

            assertTrue(chase.isCancelled)
            assertEquals(listOf(ScrollWrite.Snap(50, 0)), writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 24), coordinator.mode)
        }

    @Test
    fun paginationAndHeaderInsertionResolveTheSameMessageIdAndOffset() =
        runTest {
            val writer = RecordingScrollWriter()
            val anchor = anchor(messageId = "reader", itemId = "msg:reader", listIndex = 8, pixelOffset = 33)
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 33),
                )
            coordinator.settleReadingAt(anchor)

            coordinator.reanchorReadingHistory(resolveAnchorIndex = { saved ->
                assertEquals("reader", saved.messageId)
                assertEquals("msg:reader", saved.itemId)
                23
            })

            assertEquals(listOf(ScrollWrite.Snap(23, 33)), writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 33), coordinator.mode)
        }

    @Test
    fun newMessageArrivalDoesNotFollowTailWhileReadingHistory() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 12),
                )

            val followed =
                coordinator.followTailIfAllowed(
                    tailIndex = 101,
                    reason = ConversationScrollReason.NewMessage,
                    frameCount = 1,
                    awaitFrame = {},
                )

            assertFalse(followed)
            assertTrue(writer.writes.isEmpty())
        }

    @Test
    fun searchJumpCanRestoreThePriorLogicalAnchorAfterRowsShift() =
        runTest {
            val writer = RecordingScrollWriter()
            val prior = anchor(messageId = "reader", itemId = "msg:reader", listIndex = 12, pixelOffset = 48)
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 48),
                )
            val bookmark = coordinator.bookmark(prior)

            coordinator.programmaticJump(
                targetMessageId = "match",
                reason = ConversationScrollReason.Search,
            ) {
                animateScrollToItem(40, 120)
            }
            coordinator.settleReadingAt(anchor(messageId = "match", listIndex = 40, pixelOffset = 120))
            assertEquals(ConversationScrollMode.ReadingHistory("match", 120), coordinator.mode)
            assertFalse(coordinator.isFollowingTail)

            coordinator.restoreBookmark(bookmark, force = true) { saved ->
                assertEquals("reader", saved.messageId)
                27
            }

            assertEquals(
                listOf(
                    ScrollWrite.Animate(40, 120),
                    ScrollWrite.Snap(27, 48),
                ),
                writer.writes,
            )
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 48), coordinator.mode)
        }

    @Test
    fun tailFollowCannotSupersedeAnExplicitProgrammaticJump() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val releaseJump = CompletableDeferred<Unit>()
            val jump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.programmaticJump(
                        targetMessageId = "history",
                        reason = ConversationScrollReason.Reply,
                    ) {
                        releaseJump.await()
                        animateScrollToItem(20, 60)
                    }
                }

            val followed =
                coordinator.followTailIfAllowed(
                    tailIndex = 99,
                    reason = ConversationScrollReason.NewMessage,
                    awaitFrame = {},
                )
            releaseJump.complete(Unit)
            jump.join()

            assertFalse(followed)
            assertEquals(listOf(ScrollWrite.Animate(20, 60)), writer.writes)
        }

    @Test
    fun activeUserGestureBlocksHydrationReanchor() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            coordinator.onUserGestureStarted(anchor(messageId = "reader", listIndex = 14, pixelOffset = 5))

            val restored = coordinator.reanchorReadingHistory { 30 }

            assertFalse(restored)
            assertTrue(writer.writes.isEmpty())
        }

    @Test
    fun conversationScreenRoutesEveryLazyListWriteThroughTheCoordinator() {
        val screen = sourceFile("ConversationScreen.kt").readText()
        val coordinator = sourceFile("ConversationScrollCoordinator.kt").readText()

        assertFalse(
            "ConversationScreen must not mutate LazyListState directly",
            Regex("listState\\.(?:scrollToItem|animateScrollToItem|requestScrollToItem)\\(").containsMatchIn(screen),
        )
        assertTrue(coordinator.contains("listState.scrollToItem(index, scrollOffset)"))
        assertTrue(coordinator.contains("listState.animateScrollToItem(index, scrollOffset)"))
    }

    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/$name"),
        ).firstOrNull { it.exists() }
            ?: error("Missing conversation source file: $name")

    private fun anchor(
        messageId: String?,
        itemId: String? = messageId?.let { "msg:$it" },
        listIndex: Int,
        pixelOffset: Int = 0,
    ) = ConversationScrollAnchor(
        listIndex = listIndex,
        pixelOffset = pixelOffset,
        itemId = itemId,
        messageId = messageId,
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

    private class RecordingScrollWriter : ConversationScrollWriter {
        val writes = mutableListOf<ScrollWrite>()

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Snap(index, scrollOffset)
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Animate(index, scrollOffset)
        }
    }
}
