package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewportRestorationOwnerTest {
    @Test
    fun emptyWindowCannotChooseAnEntryOrSavedPosition() {
        assertNull(conversationViewportEntryPosition(emptyList(), "unread", 1))
        assertNull(conversationViewportSavedPosition(ConversationScrollSnapshot(8, 19), emptyList(), 1))
    }

    @Test
    fun oneFiveAndTenUnreadMessagesChooseTheRealBoundary() {
        for (count in listOf(1, 5, 10)) {
            val rows = rows(10)
            val unread = rows[10 - count].second
            val position = requireNotNull(conversationViewportEntryPosition(rows, unread, 2))
            assertEquals(count + 1, position.index)
            assertEquals(ConversationScrollMode.ReadingHistory(unread, 0), position.mode)
            assertEquals(rows[10 - count].first, position.anchor.itemId)
        }
    }

    @Test
    fun noUnreadOrUnavailableUnreadUsesTheMeasuredTail() {
        for (unread in listOf(null, "outside-window")) {
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), unread, 2))
            assertEquals(2, position.index)
            assertEquals(ConversationScrollMode.FollowingTail, position.mode)
        }
    }

    @Test
    fun savedMessageIdentityWinsOverItemIdentityAndSurvivesHeaderInsertion() {
        val restore = ConversationScrollSnapshot(100, 73, "item-1", "message-3")
        val before = requireNotNull(conversationViewportSavedPosition(restore, rows(5), 1))
        val after = requireNotNull(conversationViewportSavedPosition(restore, rows(5), 3))
        assertEquals(2, before.index)
        assertEquals(4, after.index)
        assertEquals("item-3", after.anchor.itemId)
        assertEquals(73, after.anchor.pixelOffset)
        assertEquals(ConversationScrollReason.SavedRestore, after.reason)
    }

    @Test
    fun savedItemFallbackLegacyIndexAndDeletedAnchorPreserveTheirContract() {
        val byItem = ConversationScrollSnapshot(100, 31, "item-2", "deleted")
        val byIndex = ConversationScrollSnapshot(8, 31, "deleted-item", "deleted")
        assertEquals(3, conversationViewportSavedPosition(byItem, rows(5), 1)?.index)
        val deleted = requireNotNull(conversationViewportSavedPosition(byIndex, rows(5), 1))
        assertEquals(8, deleted.index)
        assertEquals("deleted-item", deleted.anchor.itemId)
        assertEquals("deleted", deleted.anchor.messageId)
        assertEquals(3, conversationViewportSavedPosition(ConversationScrollSnapshot(3, 31), rows(5), 1)?.index)
        assertEquals(1, conversationViewportSavedPosition(ConversationScrollSnapshot(-7, 31), rows(5), 1)?.index)
    }

    @Test
    fun tailRevealsAfterOneMeasuredFrameWithoutTheHistoryDoubleWrite() =
        runTest {
            val fixture = Fixture()
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), null, 1))
            var frames = 0
            assertTrue(fixture.owner.commitInitialPosition(position, { measured() }, { frames++ }))
            assertTrue(fixture.owner.completeInitialPosition(position, structure(), 720))
            assertEquals(1, frames)
            assertEquals(listOf(1 to 0), fixture.writer.writes)
            assertTrue(fixture.coordinator.isFollowingTail)
        }

    @Test
    fun unreadHistoryWaitsForStableLayoutThenSettlesTheLogicalAnchor() =
        runTest {
            val fixture = Fixture()
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), "message-1", 1))
            var frames = 0
            assertTrue(fixture.owner.commitInitialPosition(position, { measured() }, { frames++ }))
            assertEquals(2, frames)
            assertTrue(fixture.owner.completeInitialPosition(position, structure(), 720))
            assertEquals(listOf(4 to 0, 4 to 0), fixture.writer.writes)
            fixture.owner.onStructure(structure().copy(olderHeaderCount = 1), true) { 7 }
            assertEquals(7 to 0, fixture.writer.writes.last())
            assertEquals(ConversationScrollMode.ReadingHistory("message-1", 0), fixture.coordinator.mode)
        }

    @Test
    fun savedHistoryRetainsItsPixelOffsetAndMissingIdentityFallback() =
        runTest {
            val fixture = Fixture()
            val position =
                requireNotNull(conversationViewportSavedPosition(ConversationScrollSnapshot(6, 73), rows(5), 1))
            assertTrue(fixture.owner.commitInitialPosition(position, { measured() }, {}))
            assertTrue(fixture.owner.completeInitialPosition(position, structure(), 720))
            fixture.owner.onViewportHeight(800, presentation(), navigation { null })
            assertEquals(listOf(6 to 73, 6 to 73, 6 to 73), fixture.writer.writes)
        }

    @Test
    fun unmeasuredHistoryAndTailDoNotAuthorizeReveal() =
        runTest {
            for (unread in listOf(null, "message-1")) {
                val fixture = Fixture()
                val position = requireNotNull(conversationViewportEntryPosition(rows(5), unread, 1))
                val committed =
                    fixture.owner.commitInitialPosition(position, { ConversationInitialAnchorLayout(0, null) }, {})
                assertFalse(committed)
                assertEquals(1, fixture.writer.writes.size)
            }
        }

    @Test
    fun newerCommandSupersedesAnInitialCommitWithoutAuthorizingReveal() =
        runTest {
            val fixture = Fixture()
            val pausedFrame = CompletableDeferred<Unit>()
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), "message-1", 1))
            var committed = false
            val job =
                launch {
                    committed = fixture.owner.commitInitialPosition(position, { measured() }, { pausedFrame.await() })
                }
            runCurrent()
            fixture.coordinator.programmaticJump("reply", ConversationScrollReason.Reply) { scrollToItem(12) }
            pausedFrame.complete(Unit)
            job.join()
            assertFalse(committed)
            assertEquals(listOf(4 to 0, 12 to 0), fixture.writer.writes)
        }

    @Test
    fun cancellationDuringLayoutCannotCommitAReveal() =
        runTest {
            val fixture = Fixture()
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), "message-1", 1))
            val frame = CompletableDeferred<Unit>()
            var revealed = false
            val job =
                launch {
                    if (fixture.owner.commitInitialPosition(position, { measured() }, { frame.await() })) {
                        revealed = true
                    }
                }
            runCurrent()
            job.cancel()
            job.join()
            assertFalse(revealed)
            assertEquals(listOf(4 to 0), fixture.writer.writes)
        }

    @Test
    fun disposedAccountOrChatCannotCompleteOrStartAnotherWrite() =
        runTest {
            val fixture = Fixture()
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), null, 1))
            assertFalse(fixture.owner.commitInitialPosition(position, { measured() }, { fixture.owner.dispose() }))
            assertFalse(fixture.owner.completeInitialPosition(position, structure(), 720))
            assertFalse(fixture.owner.commitInitialPosition(position, { measured() }, {}))
            fixture.owner.onViewportHeight(800, presentation(), navigation())
            fixture.owner.onStructure(structure(), true) { 12 }
            assertEquals(listOf(1 to 0), fixture.writer.writes)
        }

    @Test
    fun disposalDuringHistoryLayoutCannotPerformTheSecondInitialWrite() =
        runTest {
            val fixture = Fixture()
            val position = requireNotNull(conversationViewportEntryPosition(rows(5), "message-1", 1))
            assertFalse(fixture.owner.commitInitialPosition(position, { measured() }, { fixture.owner.dispose() }))
            assertEquals(listOf(4 to 0), fixture.writer.writes)
        }

    @Test
    fun uncommittedAndUnchangedGeometryDoNotWrite() =
        runTest {
            val fixture = Fixture()
            fixture.owner.onViewportHeight(720, presentation(), navigation())
            fixture.owner.onStructure(structure(), true) { 12 }
            fixture.gate.commit(structure(), 720)
            fixture.owner.onViewportHeight(720, presentation(), navigation())
            fixture.owner.onStructure(structure(), true) { 12 }
            assertTrue(fixture.writer.writes.isEmpty())
        }

    @Test
    fun keyboardAndUnanchoredHeightChangesUpdateBaselineWithoutACompetingCorrection() =
        runTest {
            val fixture = Fixture()
            fixture.gate.commit(structure(), 720)
            fixture.owner.onViewportHeight(400, ConversationViewportPresentation(true, true), navigation())
            fixture.owner.onViewportHeight(400, presentation(), navigation())
            fixture.owner.onViewportHeight(600, ConversationViewportPresentation(false, false), navigation())
            fixture.owner.onViewportHeight(600, presentation(), navigation())
            assertTrue(fixture.writer.writes.isEmpty())
        }

    @Test
    fun changedTailHeightUsesTheCurrentTailIndex() =
        runTest {
            val fixture = Fixture()
            fixture.gate.commit(structure(), 720)
            fixture.owner.onViewportHeight(800, presentation(), navigation())
            assertEquals(listOf(9 to 0), fixture.writer.writes)
        }

    @Test
    fun neighboringPagesReanchorTheSameMessageAndPixelOffset() =
        runTest {
            val fixture = Fixture()
            val restore = ConversationScrollSnapshot(4, 61, "item-1", "message-1")
            val position = requireNotNull(conversationViewportSavedPosition(restore, rows(5), 1))
            fixture.owner.completeInitialPosition(position, structure(), 720)
            val expanded = structure().copy(rowKeys = listOf("old" to "old") + rows(5), olderHeaderCount = 1)
            var resolvedMessage: String? = null
            fixture.owner.onStructure(expanded, true) { anchor ->
                resolvedMessage = anchor.messageId
                7
            }
            assertEquals("message-1", resolvedMessage)
            assertEquals(listOf(7 to 61), fixture.writer.writes)
            fixture.owner.onStructure(expanded, true) { 99 }
            assertEquals(1, fixture.writer.writes.size)
        }

    @Test
    fun foregroundTransactionOwnsCorrectionWhileBackgroundStructureAndHeightChange() =
        runTest {
            val fixture = Fixture()
            val geometry = ConversationForegroundGeometry(720, 0, 96)
            fixture.gate.commit(structure(), 720)
            val token =
                fixture.coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(
                        fixture.coordinator.bookmark(ConversationScrollAnchor(4, 19, "item-1", "message-1")),
                        geometry,
                        structure(),
                    ),
                )
            fixture.owner.onViewportHeight(800, presentation(), navigation())
            fixture.owner.onStructure(structure().copy(olderHeaderCount = 1), true) { 7 }
            assertTrue(fixture.writer.writes.isEmpty())
            val restored =
                fixture.coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry.copy(viewportHeightPx = 800),
                    resumedTimelineStructure = structure(),
                    resolveAnchorIndex = { 7 },
                    resolveTailIndex = { 9 },
                )
            assertTrue(restored)
            assertEquals(listOf(9 to 0), fixture.writer.writes)
        }

    @Test
    fun unanchoredStructureAndTransientNavigationDoNotCorrect() =
        runTest {
            val fixture = Fixture()
            fixture.gate.commit(structure(), 720)
            fixture.owner.onStructure(structure().copy(olderHeaderCount = 1), false) { 12 }
            val blocked = CompletableDeferred<Unit>()
            val job =
                launch {
                    fixture.coordinator.programmaticJump("reply", ConversationScrollReason.Reply) { blocked.await() }
                }
            runCurrent()
            fixture.owner.onViewportHeight(800, presentation(), navigation())
            assertTrue(fixture.writer.writes.isEmpty())
            blocked.complete(Unit)
            job.join()
        }

    /** A notified message lands hidden, and its placed offset becomes the durable reading anchor. */
    @Test
    fun notificationLandingCommitsHiddenThenSettlesWithThePlacedOffset() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            assertTrue(fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe()))
            assertEquals(listOf(7 to 300), fixture.writer.writes)
            assertTrue(fixture.owner.completeInitialPosition(position, structure(), 720))
            assertEquals(ConversationScrollMode.ReadingHistory("message-4", 300), fixture.coordinator.mode)
            assertEquals(500 to 800, fixture.owner.readingStartGeometry())
        }

    /** A tall row is only measurable after its first write, so the settled offset is the corrected one. */
    @Test
    fun notificationLandingSettlesTheCorrectedOffsetOfAnInitiallyUnmeasuredRow() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry(rowHeight = null)
            val position = landingPosition()
            val probe = geometry.probe()
            assertTrue(
                fixture.owner.commitInitialPosition(
                    position,
                    { measured() },
                    { geometry.rowHeight = 800 },
                    probe,
                ),
            )
            assertTrue(fixture.owner.completeInitialPosition(position, structure(), 720))
            assertEquals(listOf(7 to 0, 7 to 300), fixture.writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("message-4", 300), fixture.coordinator.mode)
        }

    /** A reveal is never authorized for a landing whose row could not be measured. */
    @Test
    fun anUnmeasurableNotificationLandingDoesNotAuthorizeRevealOrAnIntent() =
        runTest {
            val fixture = Fixture()
            val position = landingPosition()
            val probe = LandingGeometry(rowHeight = null).probe()
            assertFalse(fixture.owner.commitInitialPosition(position, { measured() }, {}, probe))
            assertTrue(fixture.owner.completeInitialPosition(position, structure(), 720))
            assertNull(fixture.owner.readingStartGeometry())
            assertEquals(ConversationScrollMode.ReadingHistory("message-4", 0), fixture.coordinator.mode)
        }

    /** A landing without the live geometry it needs is a wiring error, never a silent fallback. */
    @Test
    fun aReadingStartPositionWithoutAProbeIsRejected() =
        runTest {
            val fixture = Fixture()
            try {
                fixture.owner.commitInitialPosition(landingPosition(), { measured() }, {})
                fail("a reading-start commit must require its geometry probe")
            } catch (expected: IllegalArgumentException) {
                assertTrue(fixture.writer.writes.isEmpty())
            }
        }

    /** The viewport resizing reruns the measured settle rather than reapplying the old pixel offset. */
    @Test
    fun viewportChangeRerunsTheLandingSettleInsteadOfTheStalePixelOffset() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            geometry.viewportEnd = 400
            fixture.owner.onViewportHeight(800, presentation(), navigation { error("stale anchor reapplied") })
            assertEquals(listOf(7 to 300, 7 to 400), fixture.writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("message-4", 400), fixture.coordinator.mode)
        }

    /** A header or page structure change also reruns the settle against fresh geometry. */
    @Test
    fun structureChangeRerunsTheLandingSettle() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            geometry.rowHeight = 900
            fixture.owner.onStructure(structure().copy(olderHeaderCount = 1), true) { error("stale anchor reapplied") }
            assertEquals(listOf(7 to 300, 7 to 400), fixture.writer.writes)
        }

    /**
     * A wheel, key or accessibility scroll raises no drag, so the landed row leaves the screen while the landing is
     * still current. A new message then changes the structure, and neither the landing nor the ordinary reanchor may
     * drag the reader back to the notified message. The reader's own position becomes the reading position instead.
     */
    @Test
    fun aStructureChangeAfterANonDragScrollAwayKeepsTheReadersPosition() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            geometry.rowHeight = null
            val reader = ConversationScrollAnchor(3, 40, "item-1", "message-1")

            fixture.owner.onStructure(
                structure().copy(olderHeaderCount = 1),
                true,
                currentAnchor = { reader },
            ) { error("stale anchor reapplied") }
            geometry.rowHeight = 1000
            fixture.owner.onReadingStartGeometry()

            assertEquals("nothing may be written back to the landed row", listOf(7 to 300), fixture.writer.writes)
            assertNull(fixture.owner.readingStartGeometry())
            assertEquals(ConversationScrollMode.ReadingHistory("message-1", 40), fixture.coordinator.mode)
        }

    /** A viewport resize after the same scroll away is also left alone, and it adopts where the reader is. */
    @Test
    fun aViewportChangeAfterANonDragScrollAwayKeepsTheReadersPosition() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            geometry.rowHeight = null
            val reader = ConversationScrollAnchor(3, 40, "item-1", "message-1")

            fixture.owner.onViewportHeight(800, presentation(), navigation(currentAnchor = { reader }))

            assertEquals(listOf(7 to 300), fixture.writer.writes)
            assertNull(fixture.owner.readingStartGeometry())
            assertEquals(ConversationScrollMode.ReadingHistory("message-1", 40), fixture.coordinator.mode)
        }

    /** With nothing anchorable on screen the landing is still retired, and the reading position is left untouched. */
    @Test
    fun aLandingWhoseRowLeftAndWhoseReaderHasNoAnchorIsRetiredWithoutMovingTheList() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            geometry.rowHeight = null

            fixture.owner.onStructure(structure().copy(olderHeaderCount = 1), true) { error("stale anchor reapplied") }

            assertEquals(listOf(7 to 300), fixture.writer.writes)
            assertNull(fixture.owner.readingStartGeometry())
            assertEquals(ConversationScrollMode.ReadingHistory("message-4", 300), fixture.coordinator.mode)
        }

    /** Late media growth of the row alone, with no viewport or structure change, still re-settles. */
    @Test
    fun rowHeightChangeAloneRerunsTheLandingSettle() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            geometry.rowHeight = 1000
            assertEquals(500 to 1000, fixture.owner.readingStartGeometry())
            fixture.owner.onReadingStartGeometry()
            assertEquals(listOf(7 to 300, 7 to 500), fixture.writer.writes)
        }

    /** A drag retires the landing: later geometry changes fall back to the ordinary history reanchor. */
    @Test
    fun aGestureRetiresTheLandingSoNoLaterGeometryChangeMovesTheReader() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            assertEquals(500 to 800, fixture.owner.readingStartGeometry())
            fixture.coordinator.onUserGestureStarted(ConversationScrollAnchor(9, 12, "item-0", "message-0"))
            assertNull(fixture.owner.readingStartGeometry())
            geometry.viewportEnd = 400
            fixture.owner.onViewportHeight(800, presentation(), navigation())
            fixture.owner.onReadingStartGeometry()
            assertEquals(listOf(7 to 300), fixture.writer.writes)
            assertNull(fixture.owner.readingStartGeometry())
        }

    /** Another command that owns the list right now is never cancelled for a landing correction. */
    @Test
    fun aRunningCommandIsNotCancelledByALandingGeometryChange() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            val blocked = CompletableDeferred<Unit>()
            var replyCompleted = false
            val job =
                launch {
                    replyCompleted =
                        fixture.coordinator.programmaticJump("reply", ConversationScrollReason.Reply) {
                            blocked.await()
                        }
                }
            runCurrent()
            geometry.viewportEnd = 400
            fixture.owner.onReadingStartGeometry()
            blocked.complete(Unit)
            job.join()
            assertTrue(replyCompleted)
            assertEquals(listOf(7 to 300), fixture.writer.writes)
        }

    /** A disposed owner, such as one whose chat or account was replaced, never writes for a landing. */
    @Test
    fun aDisposedOwnerIgnoresLandingGeometryChanges() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            fixture.owner.dispose()
            geometry.viewportEnd = 400
            fixture.owner.onReadingStartGeometry()
            assertEquals(listOf(7 to 300), fixture.writer.writes)
        }

    /** The next initial position this owner completes replaces the landing's ownership of the viewport. */
    @Test
    fun completingAnotherInitialPositionReleasesTheLanding() =
        runTest {
            val fixture = Fixture()
            val geometry = LandingGeometry()
            val position = landingPosition()
            fixture.owner.commitInitialPosition(position, { measured() }, {}, geometry.probe())
            fixture.owner.completeInitialPosition(position, structure(), 720)
            val tail = requireNotNull(conversationViewportEntryPosition(rows(5), null, 1))
            fixture.owner.completeInitialPosition(tail, structure(), 720)
            assertNull(fixture.owner.readingStartGeometry())
        }

    /** Plans a landing on the fifth of ten rows behind two structural rows, so it is not the newest or oldest. */
    private fun landingPosition(): ConversationViewportInitialPosition {
        val planned = conversationViewportNotificationLandingPosition(rows(10), "message-4", 2)
        return requireNotNull(planned)
    }

    /** Mutable measured geometry for one landing row, as a late media measurement or a resize would change it. */
    private class LandingGeometry(
        var viewportEnd: Int = 500,
        var rowHeight: Int? = 800,
    ) {
        /** A probe resolving the landing row by identity at its planned index. */
        fun probe() =
            ConversationReadingStartProbe(
                resolveTargetIndex = { 7 },
                readLayout = { ConversationMentionJumpLayout(viewportEnd, rowHeight) },
                traceSections = false,
            )
    }

    private fun rows(count: Int) = List(count) { "item-$it" to "message-$it" }

    private fun structure() = ConversationTimelineStructure(rows(5), 0)

    private fun measured() = ConversationInitialAnchorLayout(720, 64)

    private fun presentation() = ConversationViewportPresentation(true, false)

    /** Navigation over a scripted reader anchor and anchor resolution, with the tail fixed at the last row. */
    private fun navigation(
        currentAnchor: () -> ConversationScrollAnchor? = { null },
        resolve: (ConversationScrollAnchor) -> Int? = { 7 },
    ) = ConversationViewportNavigation(
        resolveAnchor = resolve,
        currentAnchor = currentAnchor,
        tailIndex = { 9 },
    )

    private class Fixture {
        val writer = RecordingWriter()
        val coordinator = ConversationScrollCoordinator(writer)
        val gate = ConversationPostInitialReanchorGate()
        val owner = ConversationViewportRestorationOwner(coordinator, gate)
    }

    private class RecordingWriter : ConversationScrollWriter {
        override var firstVisibleItemIndex = 0
        val writes = mutableListOf<Pair<Int, Int>>()

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            firstVisibleItemIndex = index
            writes += index to scrollOffset
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = scrollToItem(index, scrollOffset)
    }
}
