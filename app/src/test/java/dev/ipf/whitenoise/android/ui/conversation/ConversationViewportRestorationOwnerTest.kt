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
                    if (fixture.owner.commitInitialPosition(position, { measured() }, { frame.await() })) revealed = true
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

    private fun rows(count: Int) = List(count) { "item-$it" to "message-$it" }

    private fun structure() = ConversationTimelineStructure(rows(5), 0)

    private fun measured() = ConversationInitialAnchorLayout(720, 64)

    private fun presentation() = ConversationViewportPresentation(true, false)

    private fun navigation(resolve: (ConversationScrollAnchor) -> Int? = { 7 }): ConversationViewportNavigation {
        return ConversationViewportNavigation(resolve) { 9 }
    }

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
