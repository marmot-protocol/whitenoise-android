package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentationVersionFfi
import dev.ipf.marmotkit.PresentedChatListSnapshotFfi
import dev.ipf.marmotkit.PresentedChatListUpdateFfi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * When MarmotKit refuses the bounded chat-list windows on an account, the pre-0.10.0 presented list keeps
 * the chat list alive. Runs under Robolectric because the refusal is logged through `android.util.Log`.
 */
@RunWith(RobolectricTestRunner::class)
class ChatListWindowFallbackTest {
    /** A refused window set is rebuilt from the whole-list handle as one live CHATS view. */
    @Test
    fun fallsBackToTheWholeListWhenWindowsAreRefused() =
        runBlocking {
            val whole = ScriptedPresentedList(rows = listOf("a", "b"))
            var refusals = 0
            val windows =
                ChatListWindowSet.open(
                    "acct",
                    openWindow = { _, _ ->
                        refusals += 1
                        throw MarmotKitException.ChatPresentationNotReady()
                    },
                    openFallback = { whole.handle },
                )

            assertEquals(1, refusals)
            assertEquals(listOf("a", "b"), windows.rows.map { it.row.groupIdHex })
            assertNull(windows.installed(ChatListViewFfi.ARCHIVED))
            assertNull(windows.pageForward(ChatListViewFfi.CHATS))
            var replacements = 0
            val receiver = launch { windows.receive { _, _ -> replacements += 1 } }
            whole.emit(sequence = 2uL, rows = listOf("a", "b", "c"))
            awaitUntil { replacements == 1 }
            assertEquals(3, windows.rows.size)
            whole.close()
            receiver.join()
        }

    /** Without a fallback the refusal propagates so the controller's retry loop owns it. */
    @Test
    fun refusedWindowsWithoutFallbackPropagate() =
        runBlocking {
            var failure: Throwable? = null
            try {
                ChatListWindowSet.open("acct", openWindow = { _, _ -> throw MarmotKitException.ChatWindowQuery("x") })
            } catch (refused: MarmotKitException) {
                failure = refused
            }
            assertTrue(failure is MarmotKitException.ChatWindowQuery)
        }

    /** The adapter presents each presented frame as a complete CHATS window with nothing more to page. */
    @Test
    fun presentedFramesBecomeWholeListWindows() =
        runBlocking {
            val whole = ScriptedPresentedList(rows = listOf("a"))
            val snapshot = whole.handle.snapshot()
            assertEquals(ChatListViewFfi.CHATS, snapshot?.view)
            assertEquals(false, snapshot?.hasMoreAfter)
            assertEquals(ChatListAnchorOutcomeFfi.Top, snapshot?.anchor)
            val unchanged = whole.handle.page(1uL, ChatListPageDirectionFfi.FORWARD, 50u)
            assertEquals(snapshot, unchanged)
        }
}

/** Polls a condition driven by the IO-dispatched receive loop, failing after five seconds. */
private suspend fun awaitUntil(condition: () -> Boolean) {
    withTimeout(5_000) {
        while (!condition()) delay(5)
    }
}

/** A scripted presented-list stream wrapped by the production adapter. */
private class ScriptedPresentedList(
    rows: List<String>,
) {
    private val updates = Channel<PresentedChatListUpdateFfi>(Channel.UNLIMITED)
    private val initial = update(1uL, rows)
    val handle =
        PresentedChatListWindowHandle(
            snapshotOnce = { initial },
            nextUpdate = { updates.receiveCatching().getOrNull() },
            release = { updates.close() },
        )

    fun emit(
        sequence: ULong,
        rows: List<String>,
    ) {
        check(updates.trySend(update(sequence, rows)).isSuccess)
    }

    fun close() = updates.close()

    private fun update(
        sequence: ULong,
        rows: List<String>,
    ) = PresentedChatListUpdateFfi(
        subscriptionGeneration = "gen",
        sequence = sequence,
        snapshot =
            PresentedChatListSnapshotFfi(
                rows = rows.map(::presentedRow),
                presentationVersion = PresentationVersionFfi(byteArrayOf(), 0uL),
            ),
    )
}
