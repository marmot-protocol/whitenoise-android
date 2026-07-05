package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccountUnreadTest {
    @Test
    fun accountUnreadCount_sumsDurableChatListRows() {
        val rows =
            listOf(
                row(groupId = "group-a", unreadCount = 2uL),
                row(groupId = "group-b", unreadCount = 0uL),
                row(groupId = "group-c", unreadCount = 5uL, archived = true),
            )

        assertEquals(2uL, accountUnreadCount(rows))
    }

    @Test
    fun accountUnreadCount_emptyRowsIsZero() {
        assertEquals(0uL, accountUnreadCount(emptyList()))
    }

    @Test
    fun accountUnreadCount_suppressesRowsWhoseLoadedRosterOmitsAccount() {
        val account = "account-b"
        val rows =
            listOf(
                row(groupId = "actionable", unreadCount = 2uL),
                row(groupId = "removed", unreadCount = 7uL),
            )

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = account,
                membersByGroupId =
                    mapOf(
                        "actionable" to listOf(member(account), member("peer-a")),
                        "removed" to listOf(member("peer-b")),
                    ),
            )

        assertEquals(2uL, count)
    }

    @Test
    fun accountUnreadCount_suppressesEmptyLoadedRoster() {
        val rows = listOf(row(groupId = "left-as-sole-member", unreadCount = 4uL))

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = "account-b",
                membersByGroupId = mapOf("left-as-sole-member" to emptyList()),
            )

        assertEquals(0uL, count)
    }

    @Test
    fun accountUnreadCount_preservesUnreadWhenRosterWasNotLoaded() {
        val rows = listOf(row(groupId = "unknown-members", unreadCount = 4uL))

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = "account-b",
                membersByGroupId = emptyMap(),
            )

        assertEquals(4uL, count)
    }

    @Test
    fun accountUnreadCount_suppressionAwareOverloadStillExcludesArchivedRows() {
        val rows = listOf(row(groupId = "archived", unreadCount = 9uL, archived = true))

        val count =
            accountUnreadCount(
                rows,
                activeAccountIdHex = "account-b",
                membersByGroupId = mapOf("archived" to listOf(member("account-b"))),
            )

        assertEquals(0uL, count)
    }

    @Test
    fun unreadRosterGroupIds_returnsDistinctUnreadUnarchivedNonBlankGroups() {
        val rows =
            listOf(
                row(groupId = "group-a", unreadCount = 2uL),
                row(groupId = "group-a", unreadCount = 4uL),
                row(groupId = "group-zero", unreadCount = 0uL),
                row(groupId = "group-archived", unreadCount = 9uL, archived = true),
                row(groupId = " ", unreadCount = 1uL),
                row(groupId = "group-b", unreadCount = 1uL),
            )

        assertEquals(listOf("group-a", "group-b"), unreadRosterGroupIds(rows))
    }

    @Test
    fun loadUnreadMemberRosters_usesBoundedConcurrentReadsForDistinctGroups() {
        runBlocking {
            val rows =
                listOf(
                    row(groupId = "group-a", unreadCount = 2uL),
                    row(groupId = "group-a", unreadCount = 4uL),
                    row(groupId = "group-b", unreadCount = 1uL),
                    row(groupId = "group-c", unreadCount = 1uL),
                    row(groupId = "group-archived", unreadCount = 9uL, archived = true),
                    row(groupId = " ", unreadCount = 1uL),
                )
            val started = ConcurrentLinkedQueue<String>()
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val firstTwoStarted = CountDownLatch(2)
            val release = CompletableDeferred<Unit>()

            val load =
                async(Dispatchers.Default) {
                    loadUnreadMemberRosters(
                        rows = rows,
                        gate = Semaphore(2),
                    ) { groupId ->
                        started += groupId
                        val now = active.incrementAndGet()
                        maxActive.updateAndGet { previous -> maxOf(previous, now) }
                        firstTwoStarted.countDown()
                        release.await()
                        active.decrementAndGet()
                        listOf(member("account-b"))
                    }
                }

            assertTrue(
                "expected two roster reads to start, saw ${started.toList()}",
                firstTwoStarted.await(2, TimeUnit.SECONDS),
            )
            Thread.sleep(50)
            assertEquals(2, started.size)
            assertEquals(2, maxActive.get())

            release.complete(Unit)
            val membersByGroupId = load.await()

            assertEquals(setOf("group-a", "group-b", "group-c"), started.toSet())
            assertEquals(listOf("group-a", "group-b", "group-c"), membersByGroupId.keys.toList())
        }
    }

    @Test
    fun accountShowsUnreadDot_trueOnlyForAccountWithOwnUnread() {
        val counts = mapOf("account-a" to 0uL, "account-b" to 3uL)

        // The account that actually has unread lights its own dot.
        assertTrue(accountShowsUnreadDot("account-b", counts))
        // The other account (e.g. the active one) does NOT light just because
        // another signed-in account has unread — the #805 misrouting invariant.
        assertFalse(accountShowsUnreadDot("account-a", counts))
    }

    @Test
    fun accountShowsUnreadDot_falseForUnknownOrBlankAccount() {
        val counts = mapOf("account-a" to 5uL)

        assertFalse(accountShowsUnreadDot("account-missing", counts))
        assertFalse(accountShowsUnreadDot("", counts))
        assertFalse(accountShowsUnreadDot(null, counts))
    }

    private fun row(
        groupId: String,
        unreadCount: ULong,
        archived: Boolean = false,
    ) = ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = groupId,
        archived = archived,
        pendingConfirmation = false,
        title = "Group $groupId",
        groupName = "",
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = unreadCount,
        hasUnread = unreadCount > 0uL,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        updatedAt = 0uL,
    )

    private fun member(accountIdHex: String) =
        AppGroupMemberRecordFfi(
            memberIdHex = accountIdHex,
            account = accountIdHex,
            local = false,
        )
}
