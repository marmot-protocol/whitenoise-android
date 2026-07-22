package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationActionRetryStoreTest {
    private val preferences: SharedPreferences =
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences("notification-action-retry-test", Context.MODE_PRIVATE)
    private var nowMillis = 1_000L
    private lateinit var store: NotificationActionRetryStore

    @Before
    fun setUp() {
        preferences.edit().clear().commit()
        nowMillis = 1_000L
        store = NotificationActionRetryStore(preferences) { nowMillis }
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun lockDeferralsDoNotConsumeOperationFailureAttempts() {
        assertTrue(store.shouldDeferForLock(WORK_KEY, maximumWaitMillis = 10_000L))
        nowMillis += 5_000L
        assertTrue(store.shouldDeferForLock(WORK_KEY, maximumWaitMillis = 10_000L))

        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))
        assertEquals(1, store.recordOperationFailureAttempt(WORK_KEY))
        assertEquals(2, store.operationFailureCount(WORK_KEY))
    }

    @Test
    fun lockWaitingExpiresAndClockRollbackStartsANewBoundedWindow() {
        assertTrue(store.shouldDeferForLock(WORK_KEY, maximumWaitMillis = 10_000L))
        nowMillis += 10_000L
        assertFalse(store.shouldDeferForLock(WORK_KEY, maximumWaitMillis = 10_000L))

        nowMillis = 500L
        assertTrue(store.shouldDeferForLock(WORK_KEY, maximumWaitMillis = 10_000L))
    }

    @Test
    fun stateSurvivesStoreRecreationAndClearRemovesIt() {
        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))
        val restored = NotificationActionRetryStore(preferences) { nowMillis }

        assertEquals(1, restored.operationFailureCount(WORK_KEY))
        restored.clear(WORK_KEY)
        assertEquals(0, restored.operationFailureCount(WORK_KEY))
        assertEquals(0, restored.recordOperationFailureAttempt(WORK_KEY))
    }

    @Test
    fun orphanedEntriesArePrunedOnceStale() {
        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))
        assertTrue(store.shouldDeferForLock(WORK_KEY, maximumWaitMillis = 10_000L))

        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS + 1L
        assertEquals(0, store.recordOperationFailureAttempt(OTHER_WORK_KEY))

        assertEquals(0, store.operationFailureCount(WORK_KEY))
        assertEquals(1, store.operationFailureCount(OTHER_WORK_KEY))
        assertTrue(preferences.all.keys.none { it.endsWith(WORK_KEY) })
    }

    @Test
    fun entriesWithRecentActivityAreRetained() {
        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))

        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS - 1L
        assertEquals(0, store.recordOperationFailureAttempt(OTHER_WORK_KEY))

        assertEquals(1, store.operationFailureCount(WORK_KEY))
    }

    @Test
    fun activityOnAnEntryRestartsItsStalenessWindow() {
        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))
        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS - 1L
        assertEquals(1, store.recordOperationFailureAttempt(WORK_KEY))

        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS - 1L
        assertEquals(0, store.recordOperationFailureAttempt(OTHER_WORK_KEY))

        assertEquals(2, store.operationFailureCount(WORK_KEY))
    }

    @Test
    fun legacyEntriesWithoutTimestampsAreGracedThenPruned() {
        // Entries persisted before staleness tracking carry no timestamp; the
        // first prune pass must stamp them (grace) instead of dropping state a
        // live worker may still be counting on.
        preferences.edit().putInt("failure_count_legacy", 2).commit()

        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))
        assertEquals(2, store.operationFailureCount("legacy"))

        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS + 1L
        assertEquals(1, store.recordOperationFailureAttempt(WORK_KEY))
        assertEquals(0, store.operationFailureCount("legacy"))
    }

    @Test
    fun clockRollbackRestampsSoEntriesAgeFromTheRollbackPoint() {
        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))

        // Roll the clock back: the future-stamped entry is restamped to "now",
        // so staleness is measured from the rollback point, not the old stamp.
        nowMillis = 500L
        assertEquals(0, store.recordOperationFailureAttempt(OTHER_WORK_KEY))
        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS + 1L
        assertEquals(1, store.recordOperationFailureAttempt(OTHER_WORK_KEY))

        assertEquals(0, store.operationFailureCount(WORK_KEY))
    }

    @Test
    fun lockDeferralAlsoPrunesStaleSiblings() {
        assertEquals(0, store.recordOperationFailureAttempt(WORK_KEY))

        nowMillis += NotificationActionRetryStore.STALE_ENTRY_MAX_AGE_MILLIS + 1L
        assertTrue(store.shouldDeferForLock(OTHER_WORK_KEY, maximumWaitMillis = 10_000L))

        assertEquals(0, store.operationFailureCount(WORK_KEY))
    }

    private companion object {
        const val WORK_KEY = "worker-a"
        const val OTHER_WORK_KEY = "worker-b"
    }
}
