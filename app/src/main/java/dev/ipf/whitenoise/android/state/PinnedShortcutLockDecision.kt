package dev.ipf.whitenoise.android.state

/** What a launcher tap may do with the App Lock state at the moment it is examined. */
internal enum class PinnedShortcutLockDecision {
    /** The foreground lock decision still waits for its unlock timestamp; keep the capability and ask again. */
    WAIT,

    /** The lock is showing or prompting; the tap fails closed to the app root. */
    LOCKED,

    /** No lock stands in the way; the capability may route. */
    OPEN,
}

/**
 * A pending evaluation is not a lock. On a cold start the unlock timestamp has not loaded when the tap is
 * parsed, so treating "pending" as locked would downgrade every pin tap made inside the grace period to Chats.
 */
internal fun pinnedShortcutLockDecision(
    appLockScreenVisible: Boolean,
    appUnlockEvaluationPending: Boolean,
): PinnedShortcutLockDecision =
    when {
        appUnlockEvaluationPending -> PinnedShortcutLockDecision.WAIT
        appLockScreenVisible -> PinnedShortcutLockDecision.LOCKED
        else -> PinnedShortcutLockDecision.OPEN
    }
