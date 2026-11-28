package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing in-chat search returns the reader to where search opened (#4224). The return can wait at a paging
 * boundary, and a second close of the same session (the toolbar and system Back together) must leave it running,
 * while a newer search session or a reader scroll still supersedes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSearchSessionTest {
    /** A second close while the return waits for paging keeps it, and the original position is restored once. */
    @Test
    fun repeatedCloseKeepsThePendingReturn() =
        runTest {
            val harness = Harness(this)
            val beforeSearch = harness.openAt(anchor("reader", listIndex = 12, pixelOffset = 48))
            harness.coordinator.settleReadingAt(anchor("match", listIndex = 40, pixelOffset = 120))

            harness.close()
            runCurrent()
            harness.close()
            runCurrent()
            harness.paging.complete(Unit)
            runCurrent()

            assertEquals(listOf(beforeSearch), harness.returnedTo)
            assertEquals(listOf(ScrollWrite(12, 48)), harness.writer.writes)
            assertFalse(harness.navigation.searchOpen)
            assertNull(harness.navigation.preSearchScrollAnchor)
        }

    /** A newer search session started while the first return still pages supersedes that return. */
    @Test
    fun newerSearchSessionSupersedesThePendingReturn() =
        runTest {
            val harness = Harness(this)
            harness.openAt(anchor("reader", listIndex = 12))
            harness.close()
            runCurrent()

            val secondSession = harness.openAt(anchor("later", listIndex = 30))
            assertEquals(
                30,
                harness.navigation.preSearchScrollAnchor
                    ?.bookmark
                    ?.anchor
                    ?.listIndex,
            )
            harness.close()
            runCurrent()
            harness.paging.complete(Unit)
            runCurrent()

            assertEquals(listOf(secondSession), harness.returnedTo)
            assertEquals(listOf(ScrollWrite(30, 0)), harness.writer.writes)
        }

    /** A reader scroll after the close is newer intent, so the pending return leaves the list where it is. */
    @Test
    fun readerScrollSupersedesThePendingReturn() =
        runTest {
            val harness = Harness(this)
            harness.openAt(anchor("reader", listIndex = 12))
            harness.close()
            runCurrent()

            harness.coordinator.onUserGestureStarted(anchor("scrolled", listIndex = 20))
            harness.paging.complete(Unit)
            runCurrent()

            assertEquals(1, harness.returnedTo.size)
            assertTrue(harness.writer.writes.isEmpty())
        }

    /** Opening a new search while the previous close still pages back leaves the transcript under the new search. */
    @Test
    fun reopeningSearchRetiresThePendingReturn() =
        runTest {
            val harness = Harness(this)
            harness.openAt(anchor("reader", listIndex = 12))
            harness.close()
            runCurrent()

            harness.openAt(anchor("match", listIndex = 40))
            harness.paging.complete(Unit)
            runCurrent()

            assertTrue(harness.returnedTo.isEmpty())
            assertTrue(harness.writer.writes.isEmpty())
            assertTrue(harness.navigation.searchOpen)
        }

    /** The search shortcut while search is open keeps the position the session opened at. */
    @Test
    fun reopeningAnOpenSessionKeepsItsFirstPosition() =
        runTest {
            val harness = Harness(this)
            val beforeSearch = harness.openAt(anchor("reader", listIndex = 12))
            harness.openAt(anchor("match", listIndex = 40))

            assertEquals(beforeSearch, harness.navigation.preSearchScrollAnchor?.bookmark)
        }

    /** The return lands immediately when nothing holds it at a paging boundary. */
    @Test
    fun closeWithoutPagingReturnsToTheSavedPosition() =
        runTest {
            val harness = Harness(this)
            harness.paging.complete(Unit)
            harness.openAt(anchor("reader", listIndex = 7, pixelOffset = 3))

            harness.close()
            runCurrent()

            assertEquals(listOf(ScrollWrite(7, 3)), harness.writer.writes)
        }

    /** Wires the session functions to a real scroll coordinator with a return held at a paging boundary. */
    private class Harness(
        private val scope: CoroutineScope,
    ) {
        val writer = RecordingWriter()
        val coordinator =
            ConversationScrollCoordinator(
                writer = writer,
                initialMode = ConversationScrollMode.ReadingHistory("reader", 0),
            )
        val navigation =
            ConversationNavigationState(
                initialFollowedLatestId = null,
                initialSeedTailAwaitingAuthoritative = false,
                surfaceState = ConversationSurfaceState(),
            )
        val paging = CompletableDeferred<Unit>()
        val returnedTo = mutableListOf<ConversationScrollBookmark>()

        /** Settles the reader at [at], opens search there and returns the bookmark the session saved. */
        fun openAt(at: ConversationScrollAnchor): ConversationScrollBookmark {
            coordinator.settleReadingAt(at)
            val bookmark = coordinator.bookmark(at)
            navigation.openSearchSession(bookmark, loadedTimeline = emptyList())
            return bookmark
        }

        /** Closes search the way the conversation screen does, capturing the intent at close time. */
        fun close() {
            val expectedIntent = coordinator.intentToken
            navigation.closeSearchSession(scope = scope, clearSearch = {}) { saved ->
                paging.await()
                returnedTo += saved.bookmark
                coordinator.restoreBookmark(saved.bookmark, expectedIntent) { it.listIndex }
            }
        }
    }

    private data class ScrollWrite(
        val index: Int,
        val offset: Int,
    )

    /** Records every scroll the coordinator writes. */
    private class RecordingWriter : ConversationScrollWriter {
        val writes = mutableListOf<ScrollWrite>()
        override var firstVisibleItemIndex = 0

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writes += ScrollWrite(index, scrollOffset)
            firstVisibleItemIndex = index
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) = scrollToItem(index, scrollOffset)
    }

    private companion object {
        /** A transcript anchor for the message [messageId] at [listIndex]. */
        fun anchor(
            messageId: String,
            listIndex: Int,
            pixelOffset: Int = 0,
        ) = ConversationScrollAnchor(
            listIndex = listIndex,
            pixelOffset = pixelOffset,
            itemId = "msg:$messageId",
            messageId = messageId,
        )
    }
}
