package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.HistoryNoticeCauseFfi
import dev.ipf.marmotkit.HistoryNoticeFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The chat list mirrors only account-wide notices and never dismisses one on the user's behalf. */
class AccountHistoryNoticesTest {
    /** Group-scoped occurrences belong to their conversation, not the chat list. */
    @Test
    fun refreshKeepsOnlyAccountWideNotices() =
        runTest {
            val owner = owner(reads = mutableListOf(listOf(ACCOUNT_WIDE, GROUP_SCOPED)))

            owner.refresh()

            assertEquals(listOf(ACCOUNT_WIDE), owner.notices)
        }

    /** An advisory read failure keeps the notice the user can still act on. */
    @Test
    fun failedReadKeepsTheShownNotices() =
        runTest {
            var fail = false
            val owner =
                AccountHistoryNotices(
                    accountRef = ACCOUNT,
                    readNotices = {
                        if (fail) throw MarmotKitException.TransportClosed()
                        listOf(ACCOUNT_WIDE)
                    },
                    dismissNotice = { _, _ -> true },
                )
            owner.refresh()
            fail = true

            owner.refresh()

            assertEquals(listOf(ACCOUNT_WIDE), owner.notices)
        }

    /** Only this account's change event triggers a re-read; other events and accounts do not. */
    @Test
    fun observeRereadsOnlyForThisAccountsChangeEvent() =
        runTest {
            val reads = mutableListOf(emptyList(), listOf(ACCOUNT_WIDE))
            var readCount = 0
            val owner =
                AccountHistoryNotices(
                    accountRef = ACCOUNT,
                    readNotices = {
                        readCount++
                        reads.removeFirst()
                    },
                    dismissNotice = { _, _ -> true },
                )
            val events =
                ArrayDeque(
                    listOf(
                        MarmotEventFfi.HistoryNoticesChanged(accountIdHex = "other-id", accountLabel = "other"),
                        MarmotEventFfi.GroupStateUpdated(
                            accountIdHex = "id",
                            accountLabel = ACCOUNT,
                            groupIdHex = "group",
                        ),
                        MarmotEventFfi.HistoryNoticesChanged(accountIdHex = "id", accountLabel = ACCOUNT),
                    ),
                )

            owner.observe { events.removeFirstOrNull() }

            assertEquals(2, readCount)
            assertEquals(listOf(ACCOUNT_WIDE), owner.notices)
        }

    /** A closed runtime ends observation quietly; the next composition starts a fresh read. */
    @Test
    fun observeEndsQuietlyWhenTheEventStreamFails() =
        runTest {
            val owner = owner(reads = mutableListOf(listOf(ACCOUNT_WIDE)))

            owner.observe { throw MarmotKitException.TransportClosed() }

            assertEquals(listOf(ACCOUNT_WIDE), owner.notices)
        }

    /** Dismissal covers exactly the shown ids, tolerates a stale one, and re-reads the runtime. */
    @Test
    fun dismissAllDismissesEachShownNoticeThenRereads() =
        runTest {
            val second = ACCOUNT_WIDE.copy(noticeId = "b".repeat(48), cause = HistoryNoticeCauseFfi.NOTIFICATION_LOSS)
            val reads = mutableListOf(listOf(ACCOUNT_WIDE, second, GROUP_SCOPED), listOf(GROUP_SCOPED))
            val dismissed = mutableListOf<Pair<String, String>>()
            val owner =
                AccountHistoryNotices(
                    accountRef = ACCOUNT,
                    readNotices = { reads.removeFirst() },
                    dismissNotice = { account, noticeId ->
                        dismissed += account to noticeId
                        // The first id went stale between the read and the tap.
                        noticeId != ACCOUNT_WIDE.noticeId
                    },
                )
            owner.refresh()

            owner.dismissAll()

            assertEquals(listOf(ACCOUNT to ACCOUNT_WIDE.noticeId, ACCOUNT to second.noticeId), dismissed)
            assertEquals(emptyList<HistoryNoticeFfi>(), owner.notices)
            assertFalse(owner.dismissing)
        }

    /** A failed dismissal leaves the notice for the runtime's next read to decide. */
    @Test
    fun failedDismissalStillRereadsAndClearsBusyState() =
        runTest {
            val reads = mutableListOf(listOf(ACCOUNT_WIDE), listOf(ACCOUNT_WIDE))
            val failures = mutableListOf<Throwable>()
            lateinit var owner: AccountHistoryNotices
            owner =
                AccountHistoryNotices(
                    accountRef = ACCOUNT,
                    readNotices = {
                        if (reads.size == 1) assertEquals(true, owner.dismissing)
                        reads.removeFirst()
                    },
                    dismissNotice = { _, _ -> throw MarmotKitException.TransportClosed() },
                    reportDismissFailure = { failures += it },
                )
            owner.refresh()

            owner.dismissAll()

            assertEquals(listOf(ACCOUNT_WIDE), owner.notices)
            assertFalse(owner.dismissing)
            assertEquals(1, failures.size)
            assertEquals(emptyList<List<HistoryNoticeFfi>>(), reads)
        }

    private fun owner(reads: MutableList<List<HistoryNoticeFfi>>) =
        AccountHistoryNotices(
            accountRef = ACCOUNT,
            readNotices = { reads.removeFirst() },
            dismissNotice = { _, _ -> true },
        )

    private companion object {
        const val ACCOUNT = "alice"
        val ACCOUNT_WIDE =
            HistoryNoticeFfi(
                noticeId = "a".repeat(48),
                cause = HistoryNoticeCauseFfi.DELIVERY_LOSS,
                groupIdHex = null,
                parkedAtMs = 1_790_000_000_000uL,
            )
        val GROUP_SCOPED =
            HistoryNoticeFfi(
                noticeId = "c".repeat(48),
                cause = HistoryNoticeCauseFfi.EPOCH_GAP,
                groupIdHex = "group",
                parkedAtMs = null,
            )
    }
}
