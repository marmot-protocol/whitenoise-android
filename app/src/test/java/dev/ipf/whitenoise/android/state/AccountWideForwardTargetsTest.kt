package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_HEX
import dev.ipf.whitenoise.android.ui.share.ACCOUNT_REF
import dev.ipf.whitenoise.android.ui.share.PEER_A
import dev.ipf.whitenoise.android.ui.share.PEER_B
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.group
import dev.ipf.whitenoise.android.ui.share.member
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            assertEquals(listOf(BEYOND), beyond?.items()?.map { it.group.groupIdHex })
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

    /** A live retained invitation supersedes an earlier eligible account-wide row, even when not mergeable. */
    @Test
    fun retainedIneligibleRowCannotBeResurrectedBySnapshot() =
        runBlocking {
            val controller = controllerWithAccountWideRead { _, _ -> listOf(presentedRow(BEYOND)) }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            assertEquals(listOf(BEYOND), snapshot.items().map { it.group.groupIdHex })
            controller.applyChatListRow(invitation(BEYOND).row)
            assertTrue(snapshot.items().isEmpty())
            assertTrue(mergeForwardTargets(controller.forwardTargets(), snapshot.items()).isEmpty())
        }

    /** A successfully loaded snapshot expires with its binding and cannot publish late roster data. */
    @Test
    fun teardownDuringHydrationExpiresTheSnapshot() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val controller =
                rosterController { _, _ ->
                    started.complete(Unit)
                    release.await()
                    listOf(member(ACCOUNT_HEX, true), member(PEER_B, false))
                }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            val hydration = async { snapshot.resolveMembers(setOf(BEYOND)) }
            started.await()
            controller.closeLiveSubscriptionsForAccountTeardown(ACCOUNT_REF)
            release.complete(Unit)
            hydration.await()
            assertFalse(snapshot.isCurrent)
            assertTrue(snapshot.items().isEmpty())
        }

    /** An authoritative local update invalidates an older off-window read and the picker retries it. */
    @Test
    fun invalidatedOffWindowRosterRetriesWithoutExpandingTheWindow() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val controller =
                rosterController { _, _ ->
                    reads += 1
                    if (reads == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                    listOf(member(ACCOUNT_HEX, true), member(if (reads == 1) PEER_A else PEER_B, false))
                }
            controller.applyChatListRow(chatRow(RETAINED))
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            val hydration = async { snapshot.resolveMembers(setOf(BEYOND)) }
            started.await()
            controller.applyLocalGroupDetails(group(RETAINED), listOf(member(ACCOUNT_HEX, true), member(PEER_A, false)))
            release.complete(Unit)
            hydration.await()
            assertEquals(2, reads)
            val ids = requireNotNull(snapshot.items().single().memberSnapshot).foldedMemberIds
            assertTrue(PEER_B in ids)
            assertFalse(PEER_A in ids)
            assertFalse(controller.containsGroup(BEYOND))
        }

    /** Dismissing the picker releases its in-flight claim so another picker can resolve the same target. */
    @Test
    fun cancellationReleasesOffWindowHydrationOwnership() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var reads = 0
            val controller =
                rosterController { _, _ ->
                    reads += 1
                    if (reads == 1) {
                        started.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                    listOf(member(ACCOUNT_HEX, true), member(PEER_B, false))
                }
            val snapshot = requireNotNull(controller.loadAccountWideForwardTargets())
            val hydration = async { snapshot.resolveMembers(setOf(BEYOND)) }
            started.await()
            hydration.cancelAndJoin()
            assertNull(snapshot.items().single().memberSnapshot)
            snapshot.resolveMembers(setOf(BEYOND))
            assertTrue(snapshot.items().single().memberSnapshot != null)
            assertEquals(2, reads)
        }

    /** Uses a real controller lifetime with a scripted local roster reader and one off-window native row. */
    private fun rosterController(roster: suspend (String, String) -> List<AppGroupMemberRecordFfi>): ChatsController {
        val state = emptyAppState()
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("no live window") },
                openChats = { _, _ -> error("no live feed") },
                presentedChatList = { _, _ -> listOf(presentedRow(BEYOND)) },
            )
        return ChatsController(state, ACCOUNT_REF, memberSnapshotLoader = roster)
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
