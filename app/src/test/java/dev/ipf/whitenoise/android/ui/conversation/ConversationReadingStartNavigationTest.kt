package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The reading-start settle shared by the mention button and notification landing, driven through a
 * recording writer so every write, its animation flag and its order are asserted exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationReadingStartNavigationTest {
    /** A measured short row is reached in one non-animated write at the signed reading-start offset. */
    @Test
    fun instantApproachWritesOnceWithoutAnimationThenSettlesOnTheMeasuredReadingStart() =
        runTest {
            val writer = RecordingWriter()
            val result =
                ConversationScrollCoordinator(writer).settleReadingStart(
                    targetMessageId = TARGET,
                    reason = ConversationScrollReason.NotificationTarget,
                    probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(500, 80) }),
                    approach = ConversationReadingStartApproach.Instant,
                )
            assertTrue(result.reached)
            assertEquals(ConversationReadingStartPlacement(5, -420), result.placement)
            assertEquals(listOf(Write(false, 5, -420)), writer.writes)
        }

    /** The committed placement is the corrected one, because a tall row is only measurable after its first write. */
    @Test
    fun committedPlacementIsThePlacedOffsetNotThePlannedOne() =
        runTest {
            val writer = RecordingWriter()
            var index = 5
            var measured = false
            val result =
                ConversationScrollCoordinator(writer).commitInitialReadingStartAnchor(
                    targetMessageId = TARGET,
                    resultingMode = ConversationScrollMode.ReadingHistory(TARGET, 0),
                    probe =
                        probe(
                            index = { index },
                            layout = { ConversationMentionJumpLayout(500, if (measured) 800 else null) },
                            awaitLayout = {
                                index = 6
                                measured = true
                            },
                        ),
                )
            assertEquals(ConversationReadingStartPlacement(6, 300), result.placement)
            assertEquals(listOf(Write(false, 5, 0), Write(false, 6, 300)), writer.writes)
        }

    /** The command's durable mode is the one the caller supplied, so the reveal can settle it afterwards. */
    @Test
    fun committedInitialAnchorLeavesTheRequestedReadingHistoryMode() =
        runTest {
            val coordinator = ConversationScrollCoordinator(RecordingWriter())
            coordinator.commitInitialReadingStartAnchor(
                targetMessageId = TARGET,
                resultingMode = ConversationScrollMode.ReadingHistory(TARGET, 0),
                probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(500, 80) }),
            )
            assertEquals(ConversationScrollMode.ReadingHistory(TARGET, 0), coordinator.mode)
        }

    /** A row that never becomes measurable cannot authorize a reveal. */
    @Test
    fun anUnmeasurableRowNeverReportsAPlacement() =
        runTest {
            val writer = RecordingWriter()
            val result =
                ConversationScrollCoordinator(writer).commitInitialReadingStartAnchor(
                    targetMessageId = TARGET,
                    resultingMode = ConversationScrollMode.ReadingHistory(TARGET, 0),
                    probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(500, null) }),
                )
            assertNull(result.placement)
            assertTrue(result.commandCompleted)
            assertEquals(listOf(Write(false, 5, 0)), writer.writes)
        }

    /** A target that vanishes from the window before the first write writes nothing at all. */
    @Test
    fun aVanishedTargetWritesNothing() =
        runTest {
            val writer = RecordingWriter()
            val result =
                ConversationScrollCoordinator(writer).settleReadingStart(
                    targetMessageId = TARGET,
                    reason = ConversationScrollReason.NotificationTarget,
                    probe = probe(index = { null }, layout = { ConversationMentionJumpLayout(500, 80) }),
                    approach = ConversationReadingStartApproach.Instant,
                )
            assertFalse(result.reached)
            assertTrue(result.commandCompleted)
            assertTrue(writer.writes.isEmpty())
        }

    /** A newer command during the layout frame stops the landing before it can write a stale correction. */
    @Test
    fun aNewerCommandDuringTheLayoutFramePreventsFurtherWrites() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var result: ConversationReadingStartResult? = null
            val landing =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    result =
                        coordinator.commitInitialReadingStartAnchor(
                            targetMessageId = TARGET,
                            resultingMode = ConversationScrollMode.ReadingHistory(TARGET, 0),
                            probe =
                                probe(
                                    index = { 5 },
                                    layout = { ConversationMentionJumpLayout(500, 800) },
                                    awaitLayout = {
                                        waiting.complete(Unit)
                                        release.await()
                                    },
                                ),
                        )
                }
            waiting.await()
            coordinator.programmaticJump("reply", ConversationScrollReason.Reply) { scrollToItem(12) }
            release.complete(Unit)
            landing.join()
            assertFalse(requireNotNull(result).reached)
            assertFalse(requireNotNull(result).commandCompleted)
            assertEquals(listOf(Write(false, 5, 300), Write(false, 12, 0)), writer.writes)
        }

    /** Starting from a row already placed, unchanged geometry costs no write at all. */
    @Test
    fun inPlaceSettleSkipsTheWriteWhenGeometryIsUnchanged() =
        runTest {
            val writer = RecordingWriter()
            val result =
                ConversationScrollCoordinator(writer).settleReadingStart(
                    targetMessageId = TARGET,
                    reason = ConversationScrollReason.ViewportChange,
                    probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(500, 800) }),
                    approach = ConversationReadingStartApproach.InPlace(ConversationReadingStartPlacement(5, 300)),
                )
            assertTrue(result.reached)
            assertTrue(writer.writes.isEmpty())
        }

    /** A viewport that changed after the landing is repaired by exactly one fresh correction. */
    @Test
    fun inPlaceSettleCorrectsAChangedViewportOnce() =
        runTest {
            val writer = RecordingWriter()
            val result =
                ConversationScrollCoordinator(writer).settleReadingStart(
                    targetMessageId = TARGET,
                    reason = ConversationScrollReason.ViewportChange,
                    probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(400, 800) }),
                    approach = ConversationReadingStartApproach.InPlace(ConversationReadingStartPlacement(5, 300)),
                )
            assertEquals(ConversationReadingStartPlacement(5, 400), result.placement)
            assertEquals(listOf(Write(false, 5, 400)), writer.writes)
        }

    /** The landing is initial placement, so it must not retire the unread jump the way an explicit jump does. */
    @Test
    fun notificationLandingDoesNotSupersedeTheUnreadJumpButMentionDoes() =
        runTest {
            var explicitNavigations = 0
            val coordinator =
                ConversationScrollCoordinator(RecordingWriter(), onExplicitNavigation = { explicitNavigations++ })
            val layoutProbe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(500, 80) })
            coordinator.settleReadingStart(
                TARGET,
                ConversationScrollReason.NotificationTarget,
                layoutProbe,
                ConversationReadingStartApproach.Instant,
            )
            assertEquals(0, explicitNavigations)
            coordinator.settleReadingStart(
                TARGET,
                ConversationScrollReason.Mention,
                layoutProbe,
                ConversationReadingStartApproach.Instant,
            )
            assertEquals(1, explicitNavigations)
        }

    /** A recorded intent reruns the settle against fresh geometry and keeps its own revision current. */
    @Test
    fun intentResettleRecordsTheCorrectedPlacementAsTheDurableAnchor() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            var viewportEnd = 500
            val anchor = ConversationScrollAnchor(5, 300, "item-5", TARGET)
            coordinator.settleReadingAt(anchor)
            val intent =
                ConversationReadingStartIntent(
                    anchor = anchor,
                    probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(viewportEnd, 800) }),
                    placement = ConversationReadingStartPlacement(5, 300),
                    intentRevision = coordinator.intentToken.revision,
                )
            assertTrue(intent.isCurrent(coordinator))
            viewportEnd = 400
            assertEquals(400 to 800, intent.geometry())
            assertTrue(intent.resettle(coordinator))
            assertEquals(listOf(Write(false, 5, 400)), writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory(TARGET, 400), coordinator.mode)
            assertTrue("the intent keeps owning the position it just corrected", intent.isCurrent(coordinator))
        }

    /** A row scrolled out of the viewport has no measured height, so leaving it can never rerun the settle. */
    @Test
    fun aRowOutsideTheViewportReportsNoGeometryAndSoNeverPullsTheListBack() =
        runTest {
            val coordinator = ConversationScrollCoordinator(RecordingWriter())
            val anchor = ConversationScrollAnchor(5, 300, "item-5", TARGET)
            coordinator.settleReadingAt(anchor)
            val intent =
                ConversationReadingStartIntent(
                    anchor = anchor,
                    probe = probe(index = { 5 }, layout = { ConversationMentionJumpLayout(500, null) }),
                    placement = ConversationReadingStartPlacement(5, 300),
                    intentRevision = coordinator.intentToken.revision,
                )

            assertTrue(intent.isCurrent(coordinator))
            assertNull(intent.geometry())
        }

    /** A drag changes the scroll intent, so the recorded landing can no longer claim the viewport. */
    @Test
    fun aGestureRetiresTheIntentAndAStaleResettleWritesNothingToIt() =
        runTest {
            val coordinator = ConversationScrollCoordinator(RecordingWriter())
            val anchor = ConversationScrollAnchor(5, 300, "item-5", TARGET)
            coordinator.settleReadingAt(anchor)
            val intent =
                ConversationReadingStartIntent(
                    anchor = anchor,
                    probe = probe(index = { null }, layout = { ConversationMentionJumpLayout(500, 800) }),
                    placement = ConversationReadingStartPlacement(5, 300),
                    intentRevision = coordinator.intentToken.revision,
                )
            coordinator.onUserGestureStarted(ConversationScrollAnchor(9, 12, "item-9", "other"))
            assertFalse(intent.isCurrent(coordinator))
            assertNull(intent.geometry())
        }

    /** A probe over scripted geometry, with tracing off. */
    private fun probe(
        index: () -> Int?,
        layout: (Int) -> ConversationMentionJumpLayout,
        awaitLayout: suspend () -> Unit = {},
    ) = ConversationReadingStartProbe(
        resolveTargetIndex = index,
        readLayout = layout,
        awaitLayout = awaitLayout,
        traceSections = false,
    )

    private data class Write(
        val animated: Boolean,
        val index: Int,
        val offset: Int,
    )

    private class RecordingWriter : ConversationScrollWriter {
        override var firstVisibleItemIndex = 0
        val writes = mutableListOf<Write>()

        /** Records a non-animated write. */
        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            writes += Write(false, index, scrollOffset)
        }

        /** Records an animated write. */
        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            writes += Write(true, index, scrollOffset)
        }
    }

    private companion object {
        const val TARGET = "target-message"
    }
}
