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
                resolveTailIndex = { 99 },
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
                resolveTailIndex = { 42 },
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
    fun multiFrameTailChaseResolvesTheCurrentTailOnEveryFrame() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var currentTailIndex = 49

            coordinator.followTailIfAllowed(
                resolveTailIndex = { currentTailIndex },
                reason = ConversationScrollReason.ImeTransition,
                frameCount = 3,
                awaitFrame = { currentTailIndex++ },
            )

            assertEquals(
                listOf(
                    ScrollWrite.Snap(50, 0),
                    ScrollWrite.Snap(51, 0),
                    ScrollWrite.Snap(52, 0),
                ),
                writer.writes,
            )
            assertTrue(coordinator.isFollowingTail)
        }

    @Test
    fun userGestureCancelsOnlyTheInFlightCommandAndLeavesItsCallerAlive() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val frames = Channel<Unit>(Channel.UNLIMITED)
            val started = CompletableDeferred<Unit>()
            var chaseResult = true
            val chase =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    chaseResult =
                        coordinator.followTailIfAllowed(
                            resolveTailIndex = { 50 },
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
            chase.join()

            assertFalse(chase.isCancelled)
            assertFalse(chaseResult)
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
    fun initialHistoryAnchorCommitsAgainOnlyAfterTargetGeometryIsStable() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 33),
                )
            val layouts =
                listOf(
                    ConversationInitialAnchorLayout(viewportHeight = 0, targetItemSize = null),
                    ConversationInitialAnchorLayout(viewportHeight = 600, targetItemSize = 240),
                    ConversationInitialAnchorLayout(viewportHeight = 600, targetItemSize = 240),
                )
            var frame = -1

            coordinator.commitInitialAnchor(
                targetMessageId = "reader",
                reason = ConversationScrollReason.SavedRestore,
                resultingMode = ConversationScrollMode.ReadingHistory("reader", 33),
                targetIndex = 18,
                pixelOffset = 33,
                captureLayout = { layouts[frame.coerceAtLeast(0)] },
                maxSettleFrames = layouts.size,
                awaitFrame = { frame++ },
            )

            assertEquals(
                listOf(
                    ScrollWrite.Snap(18, 33),
                    ScrollWrite.Snap(18, 33),
                ),
                writer.writes,
            )
        }

    @Test
    fun postInitialReanchorGateIgnoresCommittedStartupBaseline() {
        val gate = ConversationPostInitialReanchorGate()
        val initial =
            ConversationTimelineStructure(
                rowKeys = listOf("msg:1" to "1", "msg:2" to "2"),
                olderHeaderCount = 0,
            )

        assertFalse(gate.onStructure(initial))
        assertFalse(gate.onViewportHeight(600))

        gate.commit(initial, viewportHeight = 600)

        assertFalse(gate.onStructure(initial))
        assertFalse(gate.onViewportHeight(600))
        assertTrue(
            gate.onStructure(
                initial.copy(rowKeys = initial.rowKeys + ("msg:3" to "3")),
            ),
        )
        assertTrue(gate.onViewportHeight(540))
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
                    resolveTailIndex = { 101 },
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
            val closeSearchIntent = coordinator.intentToken

            coordinator.restoreBookmark(bookmark, expectedIntent = closeSearchIntent) { saved ->
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
    fun jumpToNewestAlwaysAnimatesToThePhysicalBottomAndFollowsTail() =
        runTest {
            listOf(
                ConversationScrollMode.FollowingTail,
                ConversationScrollMode.ReadingHistory("read-anchor", 14),
                ConversationScrollMode.ReadingHistory("missing-anchor", 14),
                ConversationScrollMode.ReadingHistory(null, 14),
            ).forEach { initialMode ->
                val writer = RecordingScrollWriter()
                val coordinator =
                    ConversationScrollCoordinator(
                        writer = writer,
                        initialMode = initialMode,
                    )

                val jumped = coordinator.jumpToNewest(targetIndex = 88)

                assertTrue(jumped)
                assertEquals(listOf(ScrollWrite.Animate(88, 0)), writer.writes)
                assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
                assertTrue(coordinator.isFollowingTail)
            }
        }

    @Test
    fun delayedSearchRestoreCannotOverrideANewerUserGesture() =
        runTest {
            val writer = RecordingScrollWriter()
            val prior = anchor(messageId = "prior", listIndex = 12, pixelOffset = 48)
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("prior", 48),
                )
            coordinator.settleReadingAt(prior)
            val bookmark = coordinator.bookmark(prior)
            coordinator.settleReadingAt(anchor(messageId = "search-match", listIndex = 40, pixelOffset = 120))
            val closeSearchIntent = coordinator.intentToken
            val releasePaging = CompletableDeferred<Unit>()
            var restored = true
            val delayedRestore =
                launch {
                    releasePaging.await()
                    restored =
                        coordinator.restoreBookmark(
                            bookmark = bookmark,
                            expectedIntent = closeSearchIntent,
                            resolveAnchorIndex = { 27 },
                        )
                }

            val newerAnchor = anchor(messageId = "newer", listIndex = 55, pixelOffset = 6)
            coordinator.onUserGestureStarted(newerAnchor)
            coordinator.onUserGestureSettled(newerAnchor, nearBottom = false)
            releasePaging.complete(Unit)
            delayedRestore.join()

            assertFalse(restored)
            assertTrue(writer.writes.isEmpty())
            assertEquals(ConversationScrollMode.ReadingHistory("newer", 6), coordinator.mode)
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
                    resolveTailIndex = { 99 },
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
    fun laterPauseCancelsPendingResumeRestore() =
        runTest {
            val coordinator = ResumeScrollRestoreCoordinator()
            val started = CompletableDeferred<Unit>()
            val releaseRestore = CompletableDeferred<Unit>()
            var restored = false

            coordinator.launchResumeWork(this) {
                started.complete(Unit)
                releaseRestore.await()
                restored = true
            }
            started.await()

            coordinator.cancel()
            releaseRestore.complete(Unit)
            runCurrent()

            assertFalse(restored)
        }

    @Test
    fun replacementResumeCancelsPriorRestore() =
        runTest {
            val coordinator = ResumeScrollRestoreCoordinator()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var firstRestored = false
            var replacementRestored = false

            coordinator.launchResumeWork(this) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                firstRestored = true
            }
            firstStarted.await()

            coordinator.launchResumeWork(this) {
                replacementRestored = true
            }
            releaseFirst.complete(Unit)
            runCurrent()

            assertFalse(firstRestored)
            assertTrue(replacementRestored)
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
