package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListConditionFfi
import dev.ipf.marmotkit.ChatListFilterNodeFfi
import dev.ipf.marmotkit.ChatListQueryErrorFfi
import dev.ipf.marmotkit.ChatListViewQueryFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Native folder memberships: counts over every chat, account switches, not-ready retries and refused queries. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatFolderMembershipTest {
    private val pinned = requireNotNull(chatFolderQuery(ChatFolderRule(pinnedOnly = true), emptySet(), emptySet()))

    /** A read publishes every matching chat, lowercased, with the folder's unread count. */
    @Test fun readPublishesIdsAndUnreadCount() =
        runTest {
            val membership = membership { query -> if (query.unreadNarrowed()) listOf("BB") else listOf("AA", "BB") }
            membership.refresh("acct", mapOf("f" to pinned))
            advanceUntilIdle()
            val read = requireNotNull(membership.current("acct", "f", pinned))
            assertEquals(listOf("aa", "bb"), read.ids)
            assertEquals(1, read.unreadCount)
        }

    /** An answer for a different query, or another account, is never served as current. */
    @Test fun staleQueryOrAccountIsNotCurrent() =
        runTest {
            val membership = membership { listOf("aa") }
            membership.refresh("acct", mapOf("f" to pinned))
            advanceUntilIdle()
            val edited = requireNotNull(chatFolderQuery(ChatFolderRule(groupsOnly = true), emptySet(), emptySet()))
            assertNull(membership.current("acct", "f", edited))
            assertNull(membership.current("other", "f", pinned))
        }

    /** Switching accounts drops the previous answers at once, and a read still running for it cannot publish. */
    @Test fun accountSwitchDropsAnswersAndLateReads() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val membership =
                membership { _ ->
                    gate.await()
                    listOf("aa")
                }
            membership.refresh("first", mapOf("f" to pinned))
            advanceUntilIdle()
            membership.refresh("second", emptyMap())
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("second", membership.account)
            assertTrue(membership.folders.isEmpty())
        }

    /** While presentation is not ready the read retries, and lands once MarmotKit answers. */
    @Test fun notReadyRetriesUntilAnswered() =
        runTest {
            var calls = 0
            val membership =
                membership {
                    calls++
                    if (calls <= 2) throw MarmotKitException.ChatPresentationNotReady()
                    listOf("aa")
                }
            membership.refresh("acct", mapOf("f" to pinned))
            advanceUntilIdle()
            assertEquals(listOf("aa"), membership.current("acct", "f", pinned)?.ids)
        }

    /** A refused query leaves the folder without a native answer, so its readers keep using loaded rows. */
    @Test fun refusedQueryFallsBackToLoadedRows() =
        runTest {
            val membership =
                membership { throw MarmotKitException.InvalidChatListQuery(ChatListQueryErrorFfi.TOO_MANY_NODES) }
            membership.refresh("acct", mapOf("f" to pinned))
            advanceUntilIdle()
            assertNull(membership.current("acct", "f", pinned))
        }

    /** Without the native read, nothing is answered and every folder evaluates loaded rows. */
    @Test fun missingNativeReadAnswersNothing() =
        runTest {
            val membership = ChatFolderMembership(this, null)
            membership.refresh("acct", mapOf("f" to pinned))
            advanceUntilIdle()
            assertNull(membership.current("acct", "f", pinned))
        }

    /** A store whose native read answers with [read]. */
    private fun TestScope.membership(read: suspend (ChatListViewQueryFfi) -> List<String>): ChatFolderMembership =
        ChatFolderMembership(this) { _, query -> read(query) }

    /** Whether this is a folder query narrowed to unread chats, as the chip count reads it. */
    private fun ChatListViewQueryFfi.unreadNarrowed(): Boolean = UNREAD in filter

    private companion object {
        val UNREAD = ChatListFilterNodeFfi.Condition(ChatListConditionFfi.UNREAD)
    }
}
