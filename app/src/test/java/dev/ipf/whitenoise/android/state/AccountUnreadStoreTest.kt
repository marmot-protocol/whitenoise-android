package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioral regressions for latest-wins unread projection publication. */
class AccountUnreadStoreTest {
    /** A slower bulk fold cannot overwrite the result owned by a newer refresh. */
    @Test
    fun staleBackgroundUnreadFoldCannotOverwriteANewerRefresh() {
        val store = AccountUnreadStore()
        store.updateCount(ACCOUNT_REF, 1uL)
        val previous = store.snapshot()
        val staleGeneration = store.beginRefresh()
        val currentGeneration = store.beginRefresh()

        val stale =
            store.publishRefresh(
                previous = previous,
                refreshed = mapOf(ACCOUNT_REF to confirmedUnread(9uL)),
                generation = staleGeneration,
            )

        assertTrue(stale.writtenRefs.isEmpty())
        assertEquals(1uL, store.values.getValue(ACCOUNT_REF).unreadCount)

        val current =
            store.publishRefresh(
                previous = previous,
                refreshed = mapOf(ACCOUNT_REF to confirmedUnread(2uL)),
                generation = currentGeneration,
            )

        assertEquals(setOf(ACCOUNT_REF), current.writtenRefs)
        assertEquals(2uL, store.values.getValue(ACCOUNT_REF).unreadCount)
    }

    /**
     * A mark-read mutation fences exact folds snapshotted before it: the
     * pre-mutation CONFIRMED value must not be republished over UNKNOWN, so a
     * failed post-mutation refresh degrades honestly instead of resurrecting
     * the stale dot.
     */
    @Test
    fun markUnknownFencesAnExactFoldSnapshottedBeforeTheMutation() {
        val store = AccountUnreadStore()
        store.updateCount(ACCOUNT_REF, 2uL)
        val preMutationSnapshot = store.snapshot()[ACCOUNT_REF]

        store.markUnknown(ACCOUNT_REF)
        store.publishExactIfUnchanged(
            accountRef = ACCOUNT_REF,
            previous = preMutationSnapshot,
            value = confirmedUnread(2uL),
        )

        val value = store.values.getValue(ACCOUNT_REF)
        assertEquals(AccountUnreadFreshness.UNKNOWN, value.freshness)
        assertEquals("retained evidence survives for reconciliation", 2uL, value.unreadCount)
    }

    /** The refresh scheduled by the mutation itself still publishes normally. */
    @Test
    fun anExactFoldSnapshottedAfterTheMutationStillPublishes() {
        val store = AccountUnreadStore()
        store.updateCount(ACCOUNT_REF, 2uL)
        store.markUnknown(ACCOUNT_REF)
        val postMutationSnapshot = store.snapshot()[ACCOUNT_REF]

        store.publishExactIfUnchanged(
            accountRef = ACCOUNT_REF,
            previous = postMutationSnapshot,
            value = confirmedUnread(0uL),
        )

        val value = store.values.getValue(ACCOUNT_REF)
        assertEquals(AccountUnreadFreshness.CONFIRMED, value.freshness)
        assertEquals(0uL, value.unreadCount)
    }

    /** Builds the authoritative unread value used by a completed bulk fold. */
    private fun confirmedUnread(count: ULong): AccountUnreadValue =
        AccountUnreadValue(
            unreadCount = count,
            freshness = AccountUnreadFreshness.CONFIRMED,
            hasManualUnread = false,
        )

    private companion object {
        const val ACCOUNT_REF = "account-a"
    }
}
