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

        coordinator.onNotificationRouteHandled()

        assertTrue(coordinator.shouldDismissAfterUnlock())
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

    @Test
    fun handledNotificationWhileAlreadyResumedDoesNotPoisonLaterUnlock() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onNotificationRouteObserved()
        coordinator.onNotificationRouteHandled()

        assertTrue(coordinator.shouldDismissAfterUnlock())
    }

    /** A chat link from another app owns its foreground entry, so the chat open before it keeps its notifications. */
    @Test
    fun chatLinkBetweenStartAndResumeSkipsTheRetainedConversation() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onStart(hasPendingNotificationRoute = false)
        coordinator.onInboundRoute(hasNotificationTarget = false, data = CHAT_LINK)

        assertFalse(coordinator.consumeShouldDismissOnResume())
    }

    /** A profile link leaves the open chat in front, so resume still dismisses its notifications. */
    @Test
    fun profileLinkStillDismissesTheRetainedConversation() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onStart(hasPendingNotificationRoute = false)
        coordinator.onInboundRoute(hasNotificationTarget = false, data = PROFILE_LINK)

        assertTrue(coordinator.consumeShouldDismissOnResume())
    }

    /** A malformed chat link still owns its foreground entry, since it shows its notice over the open chat. */
    @Test
    fun malformedChatLinkBetweenStartAndResumeSkipsTheRetainedConversation() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onStart(hasPendingNotificationRoute = false)
        coordinator.onInboundRoute(hasNotificationTarget = false, data = MALFORMED_CHAT_LINK)

        assertFalse(coordinator.consumeShouldDismissOnResume())
    }

    /** A chat link that arrives while the Activity is already resumed leaves the next plain resume dismissing. */
    @Test
    fun chatLinkWhileResumedDoesNotPoisonTheNextPlainResume() {
        val coordinator = ForegroundConversationDismissalCoordinator()

        coordinator.onInboundRoute(hasNotificationTarget = false, data = CHAT_LINK)
        coordinator.onStart(hasPendingNotificationRoute = false)

        assertTrue(coordinator.consumeShouldDismissOnResume())
    }

    private companion object {
        const val CHAT_LINK = "marmot://chat/0123456789abcdef0123456789abcdef"
        const val MALFORMED_CHAT_LINK = "marmot://chat/not-hex"
        const val PROFILE_LINK = "nostr:npub1sg6plzptd64u62a878hep2kev88swjh3tw00gjsfl8f237lmu63q0uf63m"
    }
}
