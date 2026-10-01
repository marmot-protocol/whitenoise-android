package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The scripted account-wide read: (account, includeArchived) to the rows MDK would present. */
private typealias AccountWideRead = suspend (String, Boolean) -> List<PresentedChatRowFfi>

/** Account-wide forward targets beyond the retained chat-list window (#2618). */
@RunWith(RobolectricTestRunner::class)
class AccountWideForwardTargetsTest {
    /** Rows the window already retains and invites that cannot be sent into are left out of the account-wide read. */
    @Test
    fun accountWideReadSkipsRetainedRowsAndIneligibleChats() =
        runBlocking {
            val reads = mutableListOf<Pair<String, Boolean>>()
            val controller =
                controllerWithAccountWideRead { account, includeArchived ->
                    reads += account to includeArchived
                    listOf(presentedRow(RETAINED), presentedRow(BEYOND), invitation(INVITE))
                }
            controller.applyChatListRow(chatRow(RETAINED))

            val beyond = controller.loadAccountWideForwardTargets()

            assertEquals(listOf(ACCOUNT_REF to true), reads)
            assertEquals(listOf(BEYOND), beyond?.map { it.group.groupIdHex })
        }

    /** A read that outlives its account binding is rejected instead of being offered under the next account. */
    @Test
    fun readFinishingAfterAccountTeardownIsRejected() =
        runBlocking {
            lateinit var controller: ChatsController
            controller =
                controllerWithAccountWideRead { account, _ ->
                    controller.closeLiveSubscriptionsForAccountTeardown(account)
                    listOf(presentedRow(BEYOND))
                }

            assertNull(controller.loadAccountWideForwardTargets())
        }

    /** A failed read leaves the picker with the retained rows rather than an error or an empty list. */
    @Test
    fun failedReadYieldsNoAccountWideTargets() =
        runBlocking {
            val controller = controllerWithAccountWideRead { _, _ -> error("storage unavailable") }
            assertNull(controller.loadAccountWideForwardTargets())
        }

    /** Retained rows keep their live projection and account-wide rows are folded in recent-first behind them. */
    @Test
    fun mergePrefersRetainedRowsAndSortsTheRest() {
        val retained = chatListItemFromProjection(chatRow(RETAINED).copy(activitySortAt = 5uL))
        val staleCopyOfRetained = chatListItemFromProjection(chatRow(RETAINED).copy(activitySortAt = 1uL))
        val beyond = chatListItemFromProjection(chatRow(BEYOND).copy(activitySortAt = 9uL))

        val merged = mergeForwardTargets(listOf(retained), listOf(staleCopyOfRetained, beyond))

        assertEquals(listOf(BEYOND, RETAINED), merged.map { it.group.groupIdHex })
        assertSame(retained, merged.single { it.group.groupIdHex == RETAINED })
        assertSame(retained, mergeForwardTargets(listOf(retained), null).single())
        assertSame(retained, mergeForwardTargets(listOf(retained), listOf(staleCopyOfRetained)).single())
    }

    /** Builds a bound controller whose account-wide read is [read]; no live windows are opened. */
    private fun controllerWithAccountWideRead(read: AccountWideRead): ChatsController {
        val appState = emptyAppState()
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("no live window in this test") },
                openChats = { _, _ -> error("no live window in this test") },
                presentedChatList = read,
            )
        return ChatsController(appState, ACCOUNT_REF) { _, _ -> emptyList() }
    }

    /** A pending invitation row: present account-wide, never a forward target. */
    private fun invitation(groupIdHex: String): PresentedChatRowFfi {
        val presented = presentedRow(groupIdHex)
        return presented.copy(row = presented.row.copy(pendingConfirmation = true))
    }

    private companion object {
        val RETAINED = "d1".repeat(32)
        val BEYOND = "d2".repeat(32)
        val INVITE = "d3".repeat(32)
    }
}
