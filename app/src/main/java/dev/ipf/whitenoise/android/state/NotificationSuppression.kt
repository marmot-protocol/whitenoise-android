package dev.ipf.whitenoise.android.state

/**
 * In-memory state deciding whether a conversation's notifications are suppressed
 * because the user is actively viewing that chat.
 *
 * Suppression may hold only when [inForeground] is true and a specific
 * conversation is active. Two independent signals drove those halves —
 * foreground state from Activity start/stop and the active conversation from a
 * Compose effect — with no single point that reset both when the task went
 * away. A chat that was on screen when its task was swiped from recents (while
 * a foreground service kept the process, and this state, alive) could leave a
 * stale "foreground + active chat" pair behind, so a later message in that chat
 * was treated as on-screen and silenced even though nothing was visible
 * (issue #821).
 *
 * Modelling it as one immutable value with explicit lifecycle transitions keeps
 * the invariant "losing the task clears the visible conversation, and only the
 * foreground half gates suppression" authoritative and unit-testable without an
 * Android context.
 */
internal data class NotificationSuppression(
    val inForeground: Boolean = false,
    val activeConversationGroupIdHex: String? = null,
    val activeConversationAccountRef: String? = null,
) {
    /** The app entered the foreground. The UI re-arms the active conversation when a chat is actually visible. */
    fun onForeground(): NotificationSuppression = copy(inForeground = true)

    /**
     * The app left the foreground (Activity stop). Keep the open chat identity in
     * memory so returning to the same still-mounted Activity can resume
     * foreground suppression without relying on Compose to re-run an unchanged
     * DisposableEffect. The [inForeground] half alone prevents suppression while
     * the UI is not visible.
     */
    fun onBackground(): NotificationSuppression = copy(inForeground = false)

    /** The task was swiped from recents: nothing is visible, so reset everything. */
    fun onTaskRemoved(): NotificationSuppression = NotificationSuppression(inForeground = false)

    /** A conversation opened (non-null group) or closed (null group) while the UI is up. */
    fun onActiveConversation(
        groupIdHex: String?,
        accountRef: String?,
    ): NotificationSuppression =
        if (groupIdHex == null) {
            copy(activeConversationGroupIdHex = null, activeConversationAccountRef = null)
        } else {
            copy(activeConversationGroupIdHex = groupIdHex, activeConversationAccountRef = accountRef)
        }
}
