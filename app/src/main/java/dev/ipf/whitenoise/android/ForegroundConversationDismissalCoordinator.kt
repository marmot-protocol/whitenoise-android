package dev.ipf.whitenoise.android

import dev.ipf.whitenoise.android.core.ChatLink

/**
 * Distinguishes a plain foreground resume from a notification-owned route.
 *
 * Android can deliver the notification intent after [MainActivity.onStart], so
 * the decision is consumed only from onResume. Intents received while the
 * Activity is already resumed do not arm a later, unrelated foreground entry.
 */
internal class ForegroundConversationDismissalCoordinator {
    private var resumePending = false
    private var notificationRouteObserved = false
    private var currentForegroundNotificationOwned = false

    fun onStart(hasPendingNotificationRoute: Boolean) {
        resumePending = true
        notificationRouteObserved = hasPendingNotificationRoute
        currentForegroundNotificationOwned = hasPendingNotificationRoute
    }

    fun onNotificationRouteObserved() {
        currentForegroundNotificationOwned = true
        if (resumePending) notificationRouteObserved = true
    }

    /**
     * Notes an inbound intent, which owns this foreground entry when [inboundIntentOwnsForeground] says so. A chat
     * link from another app reaches a stopped Activity between onStart and onResume, like a notification tap.
     */
    fun onInboundRoute(
        hasNotificationTarget: Boolean,
        data: String?,
    ) {
        if (inboundIntentOwnsForeground(hasNotificationTarget, data)) onNotificationRouteObserved()
    }

    /**
     * The route now owns the visible conversation directly. Clear only the
     * unlock guard; the pending resume decision still needs to remember that
     * this foreground entry came from a notification.
     */
    fun onNotificationRouteHandled() {
        currentForegroundNotificationOwned = false
    }

    fun consumeShouldDismissOnResume(): Boolean {
        if (!resumePending) return false
        val shouldDismiss = !notificationRouteObserved
        resumePending = false
        notificationRouteObserved = false
        return shouldDismiss
    }

    fun shouldDismissAfterUnlock(): Boolean = !currentForegroundNotificationOwned
}

/**
 * Whether an inbound intent routes this foreground entry itself, so resume must not dismiss the notifications of
 * the conversation that was open before it: a notification tap, or a chat link that resolves to its own chat or
 * notice (#4186). A profile link or a plain launch leaves the open conversation in front and still dismisses.
 */
internal fun inboundIntentOwnsForeground(
    hasNotificationTarget: Boolean,
    data: String?,
): Boolean = hasNotificationTarget || ChatLink.isChatLink(data)
