package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #2925: Select all covers the complete native view, not only the loaded window. */
class ChatListCompleteSelectionTest {
    private fun ids(count: Int): List<String> = List(count) { "%064x".format(it) }

    /** Every list size selects every chat, even though only the first 50 rows are loaded. */
    @Test fun completeSelectionCoversEveryChatBeyondTheLoadedWindow() {
        listOf(0, 1, 49, 50, 51, 100, 101, 201, 500).forEach { total ->
            val all = ids(total)
            val loaded = all.take(LOADED_WINDOW).toSet()
            val selected = mergeCompleteChatListIds(all, loaded)
            assertEquals("count for $total chats", total, selected.size)
            assertEquals("order for $total chats", all, selected.toList())
            assertEquals(
                "a complete selection keeps unloaded chats as the window moves",
                selected,
                reconcileChatListSelection(
                    selected,
                    loaded,
                    ChatListCompleteSelection(ChatListViewFfi.CHATS, resolved = true),
                ),
            )
            assertEquals(
                "a loaded-window selection still follows the loaded rows",
                selected.intersect(loaded),
                reconcileChatListSelection(selected, loaded),
            )
        }
    }

    /** Loaded rows keep their spelling so row checks still match; unloaded IDs are canonical. */
    @Test fun mergeKeepsLoadedSpellingAndCanonicalizesUnloadedIds() {
        val merged = mergeCompleteChatListIds(listOf("AA", "bb", "CC", "aa"), setOf("Aa"))
        assertEquals(listOf("Aa", "bb", "cc"), merged.toList())
    }

    /** Revalidation removes only chats MDK no longer places in the view. */
    @Test fun revalidationKeepsSurvivorsCaseInsensitively() {
        assertEquals(
            setOf("Aa", "cc"),
            retainRevalidatedChatListIds(setOf("Aa", "bb", "cc"), listOf("aa", "CC")),
        )
        assertTrue(retainRevalidatedChatListIds(setOf("aa"), emptyList()).isEmpty())
    }

    /** Only scopes whose membership MDK owns may claim a complete selection. */
    @Test fun completeViewOnlyForNativeOwnedScopes() {
        assertEquals(ChatListViewFfi.CHATS, chatListCompleteSelectAllView(false, false, false, false))
        assertEquals(ChatListViewFfi.ARCHIVED, chatListCompleteSelectAllView(true, true, false, false))
        assertNull("custom or edited folders filter locally", chatListCompleteSelectAllView(true, false, false, false))
        assertNull("unread filters locally", chatListCompleteSelectAllView(false, false, true, false))
        assertNull("search filters locally", chatListCompleteSelectAllView(false, false, false, true))
        assertNull("search inside Archived filters locally", chatListCompleteSelectAllView(true, true, false, true))
    }

    /** The not-ready refusal is retried with backoff; the result is never partial. */
    @Test fun notReadyIsRetriedThenSucceeds() =
        runTest {
            val pauses = mutableListOf<Long>()
            var reads = 0
            val result =
                resolveCompleteChatListIds(attempts = 3, retryDelayMs = 10, pause = { pauses += it }) {
                    reads++
                    if (reads < 3) throw MarmotKitException.ChatPresentationNotReady()
                    listOf("a", "b")
                }
            assertEquals(listOf("a", "b"), result)
            assertEquals(listOf(10L, 20L), pauses)
        }

    /** Exhausted retries surface the refusal so the screen keeps the loaded selection. */
    @Test fun notReadyAfterLastAttemptPropagates() =
        runTest {
            var reads = 0
            try {
                resolveCompleteChatListIds(attempts = 2, retryDelayMs = 1, pause = {}) {
                    reads++
                    throw MarmotKitException.ChatPresentationNotReady()
                }
                fail("expected the refusal")
            } catch (expected: MarmotKitException.ChatPresentationNotReady) {
                assertEquals(2, reads)
            }
        }

    /** Other failures are not retried. */
    @Test fun otherFailuresAreNotRetried() =
        runTest {
            var reads = 0
            val failure = IllegalStateException("closed")
            try {
                resolveCompleteChatListIds(pause = {}) {
                    reads++
                    throw failure
                }
                fail("expected the failure")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertEquals(1, reads)
        }

    /** Unloaded chats have unknown state, so both directions remain and per-row-only actions hide. */
    @Test fun actionsBeyondTheLoadedWindowOfferBothDirections() {
        val loaded =
            ChatListSelectionActions(
                showMarkRead = true,
                showMarkUnread = false,
                showMute = false,
                showUnmute = true,
                showPin = true,
                showUnpin = false,
                showMovePinned = true,
                archiveAction = ChatListBulkArchiveAction.Archive,
                showAlternateArchive = true,
            )
        val chats = ChatListCompleteSelection(ChatListViewFfi.CHATS, resolved = true)
        assertSame(loaded, chatListCompleteSelectionActions(loaded, chats, allLoaded = true))
        val complete = chatListCompleteSelectionActions(loaded, chats, allLoaded = false)
        assertTrue(complete.showMarkRead && complete.showMarkUnread)
        assertTrue(complete.showMute && complete.showUnmute)
        assertTrue(complete.showPin && complete.showUnpin)
        assertFalse(complete.showMovePinned)
        assertFalse(complete.showAlternateArchive)
        assertEquals(ChatListBulkArchiveAction.Archive, complete.archiveAction)
        val archived =
            chatListCompleteSelectionActions(
                loaded,
                ChatListCompleteSelection(ChatListViewFfi.ARCHIVED, resolved = true),
                allLoaded = false,
            )
        assertEquals(ChatListBulkArchiveAction.Unarchive, archived.archiveAction)
        assertFalse("archived chats cannot be pinned", archived.showPin || archived.showUnpin)
    }

    private companion object {
        const val LOADED_WINDOW = 50
    }
}
