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

    private companion object {
        const val WORK_KEY = "worker-a"
    }
}
