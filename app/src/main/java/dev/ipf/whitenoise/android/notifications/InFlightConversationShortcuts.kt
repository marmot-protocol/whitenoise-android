package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock

/**
 * Conversation shortcuts the presenter has published for a card it has not posted yet. The shortcut is published
 * under [UserEventNotificationGroup.mutationLock] before the card's platform write, which retakes the lock later, and
 * a Direct Share refresh that replaces the dynamic set in between sees no live card for it. Holding the id here keeps
 * that refresh from deleting the shortcut, so the card never names a missing shortcut. A hold that is never released,
 * because its post was cancelled, lapses after [HOLD_LIFETIME_MS].
 */
internal object InFlightConversationShortcuts {
    private val heldSince = HashMap<String, Long>()

    /** Holds [shortcutId] for one pending post. The caller already holds the shared publication lock. */
    fun hold(
        shortcutId: String,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(UserEventNotificationGroup.mutationLock) { heldSince[shortcutId] = nowMs }
    }

    /** Releases [shortcutId] once its post has been written or abandoned. */
    fun release(shortcutId: String) {
        synchronized(UserEventNotificationGroup.mutationLock) { heldSince.remove(shortcutId) }
    }

    /** Ids still held for a pending post, dropping any whose hold has lapsed. */
    fun ids(nowMs: Long = SystemClock.elapsedRealtime()): Set<String> =
        synchronized(UserEventNotificationGroup.mutationLock) {
            heldSince.entries.removeAll { nowMs - it.value > HOLD_LIFETIME_MS }
            heldSince.keys.toSet()
        }

    /** Forgets every hold, for tests that share this process-level registry. */
    internal fun clearForTests() {
        synchronized(UserEventNotificationGroup.mutationLock) { heldSince.clear() }
    }

    /** Far longer than any publish-to-post window, short enough that a lost release cannot pin a shortcut. */
    private const val HOLD_LIFETIME_MS = 60_000L
}
