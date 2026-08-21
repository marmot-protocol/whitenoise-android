package dev.ipf.whitenoise.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundConversationDismissalCoordinatorTest {
    @Test
    fun plainResumeDismissesTheRetainedVisibleConversation() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onStart(hasPendingNotificationRoute = false)

        assertTrue(coordinator.consumeShouldDismissOnResume())
        assertTrue(coordinator.shouldDismissAfterUnlock())
    }

    @Test
    fun notificationKnownBeforeStartSkipsTheRetainedConversation() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onStart(hasPendingNotificationRoute = true)

        assertFalse(coordinator.consumeShouldDismissOnResume())
        assertFalse(coordinator.shouldDismissAfterUnlock())
    }

    @Test
    fun notificationDeliveredBetweenStartAndResumeSkipsTheRetainedConversation() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onStart(hasPendingNotificationRoute = false)
        coordinator.onNotificationRouteObserved()

        assertFalse(coordinator.consumeShouldDismissOnResume())
        assertFalse(coordinator.shouldDismissAfterUnlock())
    }

    @Test
    fun notificationWhileAlreadyResumedDoesNotPoisonTheNextPlainResume() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onNotificationRouteObserved()
        coordinator.onStart(hasPendingNotificationRoute = false)

        assertTrue(coordinator.consumeShouldDismissOnResume())
        assertTrue(coordinator.shouldDismissAfterUnlock())
    }
}
