package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTargetRetryStateTest {
    @Test
    fun explicitRetryResumesCurrentFailedTargetOnce() {
        val navigation = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        retry.failed(navigation.begin())
        retry.retry()
        assertEquals(1L, retry.generation)
        retry.retry()
        assertEquals(1L, retry.generation)
    }

    @Test
    fun gestureOrNewTargetMakesFailedNavigationIneligible() {
        val navigation = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        retry.failed(navigation.begin())
        navigation.cancel()
        retry.retry()
        assertEquals(0L, retry.generation)
        retry.failed(navigation.begin())
        navigation.begin()
        retry.retry()
        assertEquals(0L, retry.generation)
    }

    @Test
    fun staleFailureCannotReplaceNewFailureOrSurviveRouteReset() {
        val navigation = MessageTargetNavigationOwner()
        val old = navigation.begin()
        val current = navigation.begin()
        val retry = SearchTargetRetryState()
        retry.failed(current)
        retry.failed(old)
        retry.retry()
        assertEquals(1L, retry.generation)
        retry.failed(navigation.begin())
        retry.clear()
        retry.retry()
        assertEquals(1L, retry.generation)
    }

    @Test
    fun explicitRetryWaitsForRecoveryAndIsConsumedOnce() {
        val navigation = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        retry.failed(navigation.begin())
        retry.retry(loadFailurePresent = true)
        retry.retry(loadFailurePresent = true)
        retry.onLoadFailureChanged(loadFailurePresent = true)
        assertEquals(0L, retry.generation)
        retry.onLoadFailureChanged(loadFailurePresent = false)
        retry.onLoadFailureChanged(loadFailurePresent = false)
        assertEquals(1L, retry.generation)
    }

    @Test
    fun recoveryWithoutExplicitRetryDoesNotRestartNavigation() {
        val retry = SearchTargetRetryState()
        retry.failed(MessageTargetNavigationOwner().begin())
        retry.onLoadFailureChanged(loadFailurePresent = false)
        assertEquals(0L, retry.generation)
    }

    @Test
    fun delayedRecoveryCannotStealNavigationAfterGestureOrNewTarget() {
        val navigation = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        retry.failed(navigation.begin())
        retry.retry(loadFailurePresent = true)
        navigation.cancel()
        retry.onLoadFailureChanged(loadFailurePresent = false)
        assertEquals(0L, retry.generation)
        retry.failed(navigation.begin())
        retry.retry(loadFailurePresent = true)
        navigation.begin()
        retry.onLoadFailureChanged(loadFailurePresent = false)
        assertEquals(0L, retry.generation)
    }

    @Test
    fun routeResetDropsDeferredRetry() {
        val retry = SearchTargetRetryState()
        retry.failed(MessageTargetNavigationOwner().begin())
        retry.retry(loadFailurePresent = true)
        retry.clear()
        retry.onLoadFailureChanged(loadFailurePresent = false)
        assertEquals(0L, retry.generation)
    }
}
