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
