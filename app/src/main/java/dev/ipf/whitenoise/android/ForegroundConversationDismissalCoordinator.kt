package dev.ipf.whitenoise.android

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
