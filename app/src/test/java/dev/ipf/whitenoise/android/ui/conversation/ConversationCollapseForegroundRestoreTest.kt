package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Collapse is explicit reader intent even after the retained transcript's draw gate opens. */
class ConversationCollapseForegroundRestoreTest {
    @Test
    fun followingTailCollapseRetiresTheReleasedForegroundToken() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val anchor = ConversationScrollAnchor(40, 0, "msg:last", "last")
            val geometry = ConversationForegroundGeometry(420, 300, 396)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(coordinator.bookmark(anchor), geometry),
                )
            coordinator.releaseForegroundRestoreGate(token)
            assertTrue(coordinator.foregroundRestorePending)
            assertFalse(coordinator.foregroundRestoreInProgress)

            // This is the actual callback's cancellation-before-settle sequence, source-bound below.
            coordinator.cancelForegroundRestoreForUserIntent()
            val keepFollowingTail = coordinator.isFollowingTail
            coordinator.settleLandingAt(anchor, atNewestEdge = keepFollowingTail)

            assertFalse(coordinator.foregroundRestorePending)
            assertTrue(coordinator.isFollowingTail)
            assertFalse(
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry.copy(viewportHeightPx = 720, imeBottomPx = 0),
                    resolveAnchorIndex = { 40 },
                    resolveTailIndex = { 42 },
                ),
            )
            assertTrue(writer.writes.isEmpty())
        }

    @Test
    fun historyCollapseKeepsItsNewAnchorInsteadOfTheCapturedTail() =
        runTest {
            val writer = RecordingWriter()
            val coordinator = ConversationScrollCoordinator(writer)
            val paused = ConversationScrollAnchor(40, 0, "msg:last", "last")
            val collapsed = ConversationScrollAnchor(12, 0, "msg:body", "body")
            val geometry = ConversationForegroundGeometry(420, 300, 396)
            val token =
                coordinator.beginForegroundRestore(
                    ConversationForegroundSnapshot(coordinator.bookmark(paused), geometry),
                )
            coordinator.releaseForegroundRestoreGate(token)

            coordinator.cancelForegroundRestoreForUserIntent()
            coordinator.settleLandingAt(collapsed, atNewestEdge = false)
            val bookmark = coordinator.bookmark(collapsed)
            assertTrue(coordinator.restoreBookmark(bookmark, resolveAnchorIndex = { 12 }))
            val writesAfterCollapse = writer.writes.toList()

            assertFalse(
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry.copy(viewportHeightPx = 720, imeBottomPx = 0),
                    resolveAnchorIndex = { 40 },
                    resolveTailIndex = { 42 },
                ),
            )
            assertEquals(listOf(12 to 0), writesAfterCollapse)
            assertEquals(writesAfterCollapse, writer.writes)
            assertEquals(ConversationScrollMode.ReadingHistory("body", 0), coordinator.mode)
        }

    @Test
    fun shippedCollapseCallbackCancelsBeforeCapturingOrSettlingItsViewport() {
        val source =
            listOf(
                File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            ).first { it.exists() }.readText()
        val start = source.indexOf("onBeforeBodyCollapse = {")
        assertTrue("The actual message collapse callback must exist", start >= 0)
        val callback = source.substring(start).substringBefore("collapseLongMessages =")
        val cancellation = callback.indexOf("scrollCoordinator.cancelForegroundRestoreForUserIntent()")
        val capturedIntent = callback.indexOf("val keepFollowingTail =")
        val settlement = callback.indexOf("scrollCoordinator.settleLandingAt(")
        assertTrue("Collapse must retire its retained foreground token first", cancellation >= 0)
        assertTrue(capturedIntent > cancellation)
        assertTrue(settlement > capturedIntent)
    }

    private class RecordingWriter : ConversationScrollWriter {
        val writes = mutableListOf<Pair<Int, Int>>()
        override var firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += index to scrollOffset
            firstVisibleItemIndex = index
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = scrollToItem(index, scrollOffset)
    }
}
