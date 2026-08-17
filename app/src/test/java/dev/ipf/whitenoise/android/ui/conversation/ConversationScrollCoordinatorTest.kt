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
@Suppress("LargeClass") // Scroll state-machine invariants share one command/writer harness.
class ConversationScrollCoordinatorTest {
    @Test
    fun unchangedForegroundGeometryPerformsNoScrollWrite() =
        runTest {
            val writer = RecordingScrollWriter()
            val anchor = anchor(messageId = "reader", listIndex = 18, pixelOffset = 72)
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 72),
                )
            val geometry =
                ConversationForegroundGeometry(
                    viewportHeightPx = 720,
                    imeBottomPx = 0,
                    bottomChromeHeightPx = 96,
                )
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor),
                        geometry = geometry,
                        timelineStructure = timelineStructure("reader"),
                    ),
                )

            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry,
                    resumedTimelineStructure = timelineStructure("reader"),
                    resolveAnchorIndex = { 31 },
                    resolveTailIndex = { 99 },
                )

            assertTrue(restored)
            assertTrue(writer.writes.isEmpty())
            assertFalse(coordinator.foregroundRestoreInProgress)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 72), coordinator.mode)
        }

    @Test
    fun changedForegroundGeometryPerformsOneTailCorrection() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val pausedGeometry =
                ConversationForegroundGeometry(
                    viewportHeightPx = 420,
                    imeBottomPx = 300,
                    bottomChromeHeightPx = 396,
                )
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "last", listIndex = 40)),
                        geometry = pausedGeometry,
                        timelineStructure = timelineStructure("last"),
                    ),
                )

            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = pausedGeometry.copy(viewportHeightPx = 720, imeBottomPx = 0),
                    resumedTimelineStructure = timelineStructure("last"),
                    resolveAnchorIndex = { 40 },
                    resolveTailIndex = { 42 },
                )

            assertTrue(restored)
            assertEquals(listOf(ScrollWrite.Snap(42, 0)), writer.writes)
            assertTrue(coordinator.isFollowingTail)
            assertFalse(coordinator.foregroundRestoreInProgress)
        }

    @Test
    fun changedForegroundGeometryKeepsThePresentationBlockedUntilCorrectionCompletes() =
        runTest {
            val writer = BlockingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val pausedGeometry = ConversationForegroundGeometry(720, 0, 96)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "last", listIndex = 40)),
                        geometry = pausedGeometry,
                        timelineStructure = timelineStructure("last"),
                    ),
                )

            val completion =
                launch {
                    coordinator.completeForegroundRestore(
                        token = token,
                        resumedGeometry = pausedGeometry.copy(imeBottomPx = 280),
                        resumedTimelineStructure = timelineStructure("last"),
                        resolveAnchorIndex = { 40 },
                        resolveTailIndex = { 42 },
                    )
                }
            writer.writeStarted.await()

            assertTrue(
                "the root draw gate must remain closed while the one correction is suspended",
                coordinator.foregroundRestoreInProgress,
            )

            writer.releaseWrite.complete(Unit)
            completion.join()
            assertFalse(coordinator.foregroundRestoreInProgress)
        }

    @Test
    fun releasedGateKeepsTheDeferredCorrectionArmed() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val pausedGeometry = ConversationForegroundGeometry(420, 300, 396)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "last", listIndex = 40)),
                        geometry = pausedGeometry,
                        timelineStructure = timelineStructure("last"),
                    ),
                )

            coordinator.releaseForegroundRestoreGate(token)

            assertFalse(coordinator.foregroundRestoreInProgress)
            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = pausedGeometry.copy(viewportHeightPx = 720, imeBottomPx = 0),
                    resumedTimelineStructure = timelineStructure("last"),
                    resolveAnchorIndex = { 40 },
                    resolveTailIndex = { 42 },
                )

            assertTrue(restored)
            assertEquals(listOf(ScrollWrite.Snap(42, 0)), writer.writes)
        }

    @Test
    fun userGestureDiscardsAReleasedDeferredCorrection() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val geometry = ConversationForegroundGeometry(720, 0, 96)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "paused", listIndex = 18)),
                        geometry = geometry,
                        timelineStructure = timelineStructure("paused"),
                    ),
                )

            coordinator.releaseForegroundRestoreGate(token)
            coordinator.onUserGestureStarted(anchor(messageId = "gesture", listIndex = 12, pixelOffset = 24))
            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry.copy(viewportHeightPx = 700),
                    resumedTimelineStructure = timelineStructure("paused"),
                    resolveAnchorIndex = { 18 },
                    resolveTailIndex = { 99 },
                )

            assertFalse(restored)
            assertTrue(writer.writes.isEmpty())
        }

    @Test
    fun userGestureCancelsPendingForegroundRestore() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val geometry =
                ConversationForegroundGeometry(
                    viewportHeightPx = 720,
                    imeBottomPx = 0,
                    bottomChromeHeightPx = 96,
                )
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "paused", listIndex = 18)),
                        geometry = geometry,
                    ),
                )

            val gestureAnchor = anchor(messageId = "gesture", listIndex = 12, pixelOffset = 24)
            coordinator.onUserGestureStarted(gestureAnchor)
            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry.copy(viewportHeightPx = 700),
                    resolveAnchorIndex = { 31 },
                    resolveTailIndex = { 99 },
                )

            assertFalse(restored)
            assertTrue(writer.writes.isEmpty())
            assertFalse(coordinator.foregroundRestoreInProgress)
            assertEquals(ConversationScrollMode.ReadingHistory("gesture", 24), coordinator.mode)
        }

    @Test
    fun newerProgrammaticNavigationCancelsPendingForegroundRestore() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val geometry =
                ConversationForegroundGeometry(
                    viewportHeightPx = 720,
                    imeBottomPx = 0,
                    bottomChromeHeightPx = 96,
                )
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "paused", listIndex = 18)),
                        geometry = geometry,
                    ),
                )

            coordinator.programmaticJump(
                targetMessageId = "newer",
                reason = ConversationScrollReason.Reply,
                resultingMode = ConversationScrollMode.ReadingHistory("newer", 12),
            ) {
                scrollToItem(44, 12)
            }
            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry.copy(viewportHeightPx = 700),
                    resolveAnchorIndex = { 31 },
                    resolveTailIndex = { 99 },
                )

            assertFalse(restored)
            assertEquals(listOf(ScrollWrite.Snap(44, 12)), writer.writes)
            assertFalse(coordinator.foregroundRestoreInProgress)
            assertEquals(ConversationScrollMode.ReadingHistory("newer", 12), coordinator.mode)
        }

    @Test
    fun backgroundAppendDefersPassiveTailWriteAndCommitsOnceOnResume() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val geometry =
                ConversationForegroundGeometry(
                    viewportHeightPx = 720,
                    imeBottomPx = 0,
                    bottomChromeHeightPx = 96,
                )
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "last", listIndex = 40)),
                        geometry = geometry,
                        timelineStructure = timelineStructure("last"),
                    ),
                )

            val followedWhilePaused =
                coordinator.followTailIfAllowed(
                    resolveTailIndex = { 41 },
                    reason = ConversationScrollReason.NewMessage,
                    awaitFrame = {},
                )

            assertFalse(followedWhilePaused)
            assertTrue(writer.writes.isEmpty())
            assertTrue(coordinator.foregroundRestoreInProgress)

            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry,
                    resumedTimelineStructure = timelineStructure("last", "new"),
                    resolveAnchorIndex = { 40 },
                    resolveTailIndex = { 41 },
                )

            assertTrue(restored)
            assertEquals(listOf(ScrollWrite.Snap(41, 0)), writer.writes)
            assertFalse(coordinator.foregroundRestoreInProgress)
        }

    @Test
    fun backgroundStructureChangeDefersHistoryReanchorUntilForegroundCommit() =
        runTest {
            val writer = RecordingScrollWriter()
            val anchor = anchor(messageId = "reader", listIndex = 18, pixelOffset = 72)
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 72),
                )
            coordinator.settleReadingAt(anchor)
            val geometry =
                ConversationForegroundGeometry(
                    viewportHeightPx = 720,
                    imeBottomPx = 0,
                    bottomChromeHeightPx = 96,
                )
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor),
                        geometry = geometry,
                        timelineStructure = timelineStructure("reader"),
                    ),
                )

            val reanchoredWhilePaused = coordinator.reanchorReadingHistory { 31 }

            assertFalse(reanchoredWhilePaused)
            assertTrue(writer.writes.isEmpty())
            assertTrue(coordinator.foregroundRestoreInProgress)

            val restored =
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry,
                    resumedTimelineStructure = timelineStructure("older", "reader"),
                    resolveAnchorIndex = { 31 },
                    resolveTailIndex = { 99 },
                )

            assertTrue(restored)
            assertEquals(listOf(ScrollWrite.Snap(31, 72)), writer.writes)
            assertFalse(coordinator.foregroundRestoreInProgress)
        }

    @Test
    fun pausingCancelsAnInFlightPassiveScrollBeforeItCanWrite() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val frameRequested = CompletableDeferred<Unit>()
            val releaseFrame = CompletableDeferred<Unit>()
            var passiveResult = true
            val passiveScroll =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    passiveResult =
                        coordinator.followTailIfAllowed(
                            resolveTailIndex = { 41 },
                            reason = ConversationScrollReason.NewMessage,
                            awaitFrame = {
                                frameRequested.complete(Unit)
                                releaseFrame.await()
                            },
                        )
                }
            frameRequested.await()

            coordinator.beginForegroundRestore(
                ConversationForegroundSnapshot(
                    scrollBookmark = coordinator.bookmark(anchor(messageId = "last", listIndex = 40)),
                    geometry = ConversationForegroundGeometry(720, 0, 96),
                    timelineStructure = timelineStructure("last"),
                ),
            )
            releaseFrame.complete(Unit)
            passiveScroll.join()

            assertFalse(passiveResult)
            assertTrue(writer.writes.isEmpty())
            assertTrue(coordinator.foregroundRestoreInProgress)
            assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
        }

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
            val pausedGeometry = ConversationForegroundGeometry(720, 0, 96)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor),
                        geometry = pausedGeometry,
                        timelineStructure = timelineStructure("reader"),
                    ),
                )

            coordinator.completeForegroundRestore(
                token = token,
                resumedGeometry = pausedGeometry.copy(viewportHeightPx = 540),
                resumedTimelineStructure = timelineStructure("reader"),
                resolveAnchorIndex = { 31 },
                resolveTailIndex = { 99 },
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
    fun followingTailResumeAppliesOneCorrectionForChangedGeometry() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val pausedGeometry = ConversationForegroundGeometry(720, 0, 96)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        scrollBookmark = coordinator.bookmark(anchor(messageId = "last", listIndex = 40)),
                        geometry = pausedGeometry,
                        timelineStructure = timelineStructure("last"),
                    ),
                )

            coordinator.completeForegroundRestore(
                token = token,
                resumedGeometry = pausedGeometry.copy(imeBottomPx = 280),
                resumedTimelineStructure = timelineStructure("last"),
                resolveAnchorIndex = { 40 },
                resolveTailIndex = { 42 },
            )

            assertEquals(listOf(ScrollWrite.Snap(42, 0)), writer.writes)
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
    fun reactionTailSettleWaitsForDelayedRowMeasurementAndStableGeometry() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var frame = 0
            var lastRowHeight = 48

            val settled =
                coordinator.settleTailAfterLayoutChange(
                    resolveTailIndex = { 50 },
                    captureLayout = {
                        ConversationTailLayout(
                            lastRowHeightPx = lastRowHeight,
                            tailOffsetPx = 420,
                            tailSizePx = 4,
                            viewportEndOffsetPx = 424,
                        )
                    },
                    awaitFrame = {
                        frame++
                        if (frame == 6) lastRowHeight = 76
                    },
                )

            assertTrue(settled)
            assertEquals(8, frame)
            assertEquals(9, writer.writes.size)
            assertTrue(writer.writes.all { it == ScrollWrite.Snap(50, 0) })
        }

    @Test
    fun userGestureCancelsReactionTailSettleBeforeAnotherCorrection() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val frames = Channel<Unit>(Channel.UNLIMITED)
            val started = CompletableDeferred<Unit>()
            var settleResult = true
            val settle =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    settleResult =
                        coordinator.settleTailAfterLayoutChange(
                            resolveTailIndex = { 50 },
                            captureLayout = {
                                ConversationTailLayout(
                                    lastRowHeightPx = 76,
                                    tailOffsetPx = 420,
                                    tailSizePx = 4,
                                    viewportEndOffsetPx = 424,
                                )
                            },
                            awaitFrame = {
                                started.complete(Unit)
                                frames.receive()
                            },
                        )
                }
            started.await()
            assertEquals(listOf(ScrollWrite.Snap(50, 0)), writer.writes)

            coordinator.onUserGestureStarted(anchor(messageId = "reader", listIndex = 10, pixelOffset = 24))
            repeat(8) { frames.trySend(Unit) }
            settle.join()

            assertFalse(settleResult)
            assertEquals(listOf(ScrollWrite.Snap(50, 0)), writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 24), coordinator.mode)
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

            val committed =
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

            assertTrue(committed)
            assertEquals(
                listOf(
                    ScrollWrite.Snap(18, 33),
                    ScrollWrite.Snap(18, 33),
                ),
                writer.writes,
            )
        }

    @Test
    fun initialAnchorDoesNotCommitWhenLayoutNeverStabilizes() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var targetSize = 100

            val committed =
                coordinator.commitInitialAnchor(
                    targetMessageId = null,
                    reason = ConversationScrollReason.InitialAnchor,
                    resultingMode = ConversationScrollMode.FollowingTail,
                    targetIndex = 24,
                    captureLayout = {
                        ConversationInitialAnchorLayout(
                            viewportHeight = 600,
                            targetItemSize = targetSize++,
                        )
                    },
                    maxSettleFrames = 3,
                    awaitFrame = {},
                )

            assertFalse(committed)
            assertEquals(listOf(ScrollWrite.Snap(24, 0)), writer.writes)
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
                    ScrollWrite.Snap(30, 0),
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
                assertEquals(
                    listOf(
                        ScrollWrite.Snap(78, 0),
                        ScrollWrite.Animate(88, 0),
                    ),
                    writer.writes,
                )
                assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
                assertTrue(coordinator.isFollowingTail)
            }
        }

    @Test
    fun jumpToUnreadOrNewest_topAlignsUnreadThenSecondTapFollowsTail() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 14),
                )

            val firstOutcome =
                coordinator.jumpToUnreadOrNewest(
                    pendingUnreadMessageId = "unread",
                    resolveUnreadIndex = { 40 },
                    isUnreadTopAligned = { writer.firstVisibleItemIndex == 40 },
                    resolveTailIndex = { 88 },
                )

            assertEquals(ConversationJumpToNewestOutcome.UnreadStart, firstOutcome)
            assertEquals(
                listOf(
                    ScrollWrite.Snap(30, 0),
                    ScrollWrite.Animate(40, 0),
                ),
                writer.writes,
            )
            assertEquals(ConversationScrollMode.ReadingHistory("unread", 0), coordinator.mode)
            val followedNewArrival =
                coordinator.followTailIfAllowed(
                    resolveTailIndex = { 89 },
                    reason = ConversationScrollReason.NewMessage,
                    awaitFrame = {},
                )
            assertFalse(followedNewArrival)

            val secondOutcome =
                coordinator.jumpToUnreadOrNewest(
                    pendingUnreadMessageId = null,
                    resolveUnreadIndex = { null },
                    isUnreadTopAligned = { false },
                    resolveTailIndex = { 88 },
                )

            assertEquals(ConversationJumpToNewestOutcome.Tail, secondOutcome)
            assertEquals(
                listOf(
                    ScrollWrite.Snap(30, 0),
                    ScrollWrite.Animate(40, 0),
                    ScrollWrite.Snap(78, 0),
                    ScrollWrite.Animate(88, 0),
                ),
                writer.writes,
            )
            assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
        }

    @Test
    fun jumpToUnreadOrNewest_alreadyAlignedOrMissingTargetFallsThroughToTail() =
        runTest {
            listOf(true, false).forEach { targetIsAligned ->
                val writer = RecordingScrollWriter()
                val coordinator = ConversationScrollCoordinator(writer)

                val outcome =
                    coordinator.jumpToUnreadOrNewest(
                        pendingUnreadMessageId = "unread",
                        resolveUnreadIndex = { if (targetIsAligned) 40 else null },
                        isUnreadTopAligned = { targetIsAligned },
                        resolveTailIndex = { 88 },
                    )

                assertEquals(ConversationJumpToNewestOutcome.Tail, outcome)
                assertEquals(
                    listOf(
                        ScrollWrite.Snap(78, 0),
                        ScrollWrite.Animate(88, 0),
                    ),
                    writer.writes,
                )
                assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
            }
        }

    @Test
    fun jumpToUnreadOrNewest_completedButUnalignedTargetFallsThroughToTail() =
        runTest {
            val writer = RecordingScrollWriter()
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 14),
                )

            val outcome =
                coordinator.jumpToUnreadOrNewest(
                    pendingUnreadMessageId = "unread",
                    resolveUnreadIndex = { 40 },
                    isUnreadTopAligned = { false },
                    resolveTailIndex = { 88 },
                )

            assertEquals(ConversationJumpToNewestOutcome.Tail, outcome)
            assertEquals(
                listOf(
                    ScrollWrite.Snap(30, 0),
                    ScrollWrite.Animate(40, 0),
                    ScrollWrite.Snap(78, 0),
                    ScrollWrite.Animate(88, 0),
                ),
                writer.writes,
            )
            assertEquals(ConversationScrollMode.FollowingTail, coordinator.mode)
        }

    @Test
    fun explicitNavigationRetiresUnreadStackButLayoutReanchorsDoNot() =
        runTest {
            var retireCount = 0
            val coordinator =
                ConversationScrollCoordinator(
                    writer = RecordingScrollWriter(),
                    onExplicitNavigation = { retireCount++ },
                )

            coordinator.programmaticJump(targetMessageId = null, reason = ConversationScrollReason.ViewportChange) {
                animateScrollToItem(1)
            }
            coordinator.programmaticJump(targetMessageId = null, reason = ConversationScrollReason.Search) {
                animateScrollToItem(2)
            }
            coordinator.programmaticJump(targetMessageId = null, reason = ConversationScrollReason.Send) {
                animateScrollToItem(3)
            }

            assertEquals(2, retireCount)
        }

    @Test
    fun userDragCancelsUnreadJumpWithoutReportingItConsumed() =
        runTest {
            val writer = BlockingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var outcome: ConversationJumpToNewestOutcome? = null
            val jump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    outcome =
                        coordinator.jumpToUnreadOrNewest(
                            pendingUnreadMessageId = "unread",
                            resolveUnreadIndex = { 40 },
                            isUnreadTopAligned = { false },
                            resolveTailIndex = { 88 },
                        )
                }
            writer.writeStarted.await()

            coordinator.onUserGestureStarted(anchor(messageId = "reader", listIndex = 12, pixelOffset = 24))
            jump.join()

            assertFalse(jump.isCancelled)
            assertEquals(ConversationJumpToNewestOutcome.Cancelled, outcome)
            assertEquals(ConversationScrollMode.ReadingHistory("reader", 24), coordinator.mode)
        }

    @Test
    fun userDragCancelsAlignedUnreadTailJumpWithoutReportingItConsumed() =
        runTest {
            val writer = BlockingScrollWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var outcome: ConversationJumpToNewestOutcome? = null
            val jump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    outcome =
                        coordinator.jumpToUnreadOrNewest(
                            pendingUnreadMessageId = "unread",
                            resolveUnreadIndex = { 40 },
                            isUnreadTopAligned = { true },
                            resolveTailIndex = { 88 },
                        )
                }
            writer.writeStarted.await()

            coordinator.onUserGestureStarted(anchor(messageId = "unread", listIndex = 40))
            jump.join()

            assertFalse(jump.isCancelled)
            assertEquals(ConversationJumpToNewestOutcome.Cancelled, outcome)
            assertEquals(ConversationScrollMode.ReadingHistory("unread", 0), coordinator.mode)
        }

    @Test
    fun newerExplicitCommandCancelsUnreadJumpAndRetiresItsIntent() =
        runTest {
            val writer = BlockingScrollWriter()
            var retireCount = 0
            val coordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("reader", 14),
                    onExplicitNavigation = { retireCount++ },
                )
            var unreadOutcome: ConversationJumpToNewestOutcome? = null
            val unreadJump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    unreadOutcome =
                        coordinator.jumpToUnreadOrNewest(
                            pendingUnreadMessageId = "unread",
                            resolveUnreadIndex = { 40 },
                            isUnreadTopAligned = { false },
                            resolveTailIndex = { 88 },
                        )
                }
            writer.writeStarted.await()

            var searchCompleted = false
            val searchJump =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    searchCompleted =
                        coordinator.programmaticJump(
                            targetMessageId = "search-result",
                            reason = ConversationScrollReason.Search,
                        ) {
                            scrollToItem(12)
                        }
                }
            writer.releaseWrite.complete(Unit)
            unreadJump.join()
            searchJump.join()

            assertEquals(ConversationJumpToNewestOutcome.Cancelled, unreadOutcome)
            assertTrue(searchCompleted)
            assertEquals(1, retireCount)
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
            assertEquals(
                listOf(
                    ScrollWrite.Snap(10, 0),
                    ScrollWrite.Animate(20, 60),
                ),
                writer.writes,
            )
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
    fun conversationScreenClearsOwnedHighlightsWhenNavigationIsCancelled() {
        val screen = sourceFile("ConversationScreen.kt").readText()
        val highlightFunction =
            screen
                .substringAfter("suspend fun showTransientMessageHighlight(messageId: String)")
                .substringBefore("fun navigateToReplyTarget")

        assertTrue(screen.contains("var transientHighlightOwner by mutableStateOf<Any?>(null)"))
        assertTrue(highlightFunction.contains("val owner = Any()"))
        assertTrue(
            highlightFunction.contains(
                "try {\n            delay(1_500L)\n        } finally {",
            ),
        )
        assertTrue(highlightFunction.contains("if (navigationState.transientHighlightOwner === owner)"))
        assertEquals(5, Regex("showTransientMessageHighlight\\(").findAll(screen).count())
    }

    @Test
    fun conversationScreenHighlightsOnlyCompletedCenteringCommands() {
        val screen = sourceFile("ConversationScreen.kt").readText()

        assertEquals(4, Regex("if \\(!centered\\)").findAll(screen).count())
    }

    @Test
    fun conversationScreenReResolvesMessageBackedTargetsBeforeTheFinalAnimation() {
        val screen = sourceFile("ConversationScreen.kt").readText()

        assertTrue(
            screen.contains(
                """animateScrollToItem(targetIndex, animatedOffset) {
                    currentTimelineListIndex(targetMessageId) ?: targetIndex
                }""",
            ),
        )
    }

    @Test
    fun conversationScreenUsesOneForegroundTransactionWithoutFrameDelayedRestore() {
        // The pause/resume machinery lives in ConversationForegroundRestoreEffects.kt,
        // hoisted out of the screen body so the debug dex stays within what ART's
        // bytecode verifier accepts.
        val screen = sourceFile("ConversationScreen.kt").readText()
        val effects = sourceFile("ConversationForegroundRestoreEffects.kt").readText()
        val presentation = sourceFile("ConversationForegroundPresentation.kt").readText()

        assertTrue(effects.contains("scrollCoordinator.beginForegroundRestore("))
        assertTrue(effects.contains("scrollCoordinator.completeForegroundRestore("))
        assertTrue(screen.contains("scrollCoordinator.foregroundRestoreInProgress"))
        assertTrue(effects.contains("ConversationForegroundDrawGateEffect"))
        assertTrue(effects.contains("foregroundPreDrawSignals"))
        assertTrue(effects.contains("awaitConversationForegroundPresentation("))
        assertTrue(effects.contains("WindowInsets.imeAnimationTarget"))
        assertTrue(effects.contains("expectedImeVisible = restoreToken.expectedImeVisible || restoreFocus"))
        assertTrue(presentation.contains("it.isSettled(expectedImeVisible)"))
        assertTrue(presentation.contains("it.isGeometrySettled()"))
        assertFalse(screen.contains("RESUME_IME_SETTLE_MAX_FRAMES"))
        assertFalse(effects.contains("RESUME_IME_SETTLE_MAX_FRAMES"))
        assertFalse(screen.contains("scrollCoordinator.restoreViewport("))
        assertFalse(effects.contains("scrollCoordinator.restoreViewport("))
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

    private fun timelineStructure(vararg messageIds: String) =
        ConversationTimelineStructure(
            rowKeys = messageIds.map { messageId -> "msg:$messageId" to messageId },
            olderHeaderCount = 0,
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
        override var firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Snap(index, scrollOffset)
            firstVisibleItemIndex = index
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite.Animate(index, scrollOffset)
            firstVisibleItemIndex = index
        }
    }

    private class BlockingScrollWriter : ConversationScrollWriter {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        override var firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writeStarted.complete(Unit)
            releaseWrite.await()
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writeStarted.complete(Unit)
            releaseWrite.await()
        }
    }
}
