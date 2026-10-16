package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock

/**
 * Conversation shortcuts the presenter has published for a card it has not posted yet. The shortcut is published
 * under [UserEventNotificationGroup.mutationLock] before the card's platform write, which retakes the lock later, and
 * a Direct Share refresh that replaces the dynamic set in between sees no live card for it. Holding the id here keeps
 * that refresh from deleting the shortcut, so the card never names a missing shortcut. Posts for one conversation can
 * overlap under one shortcut id, so holds are counted and the id stays held until the last one is released. A hold that
 * is never released lapses [HOLD_LIFETIME_MS] after the latest hold.
 */
internal object InFlightConversationShortcuts {
    /** How many pending posts hold one shortcut id, and when the latest of them took its hold. */
    private data class Hold(
        val count: Int,
        val latestMs: Long,
    )

    private val holds = HashMap<String, Hold>()

    /** Adds one hold on [shortcutId] for a pending post. The caller already holds the shared publication lock. */
    fun hold(
        shortcutId: String,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            holds[shortcutId] = Hold((holds[shortcutId]?.count ?: 0) + 1, nowMs)
        }
    }

    /** Releases one hold on [shortcutId] once its post has been written or abandoned, freeing it after the last. */
    fun release(shortcutId: String) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            val held = holds[shortcutId] ?: return
            if (held.count <= 1) holds.remove(shortcutId) else holds[shortcutId] = held.copy(count = held.count - 1)
        }
    }

    /** Ids still held for a pending post, dropping any whose latest hold has lapsed. */
    fun ids(nowMs: Long = SystemClock.elapsedRealtime()): Set<String> =
        synchronized(UserEventNotificationGroup.mutationLock) {
            holds.entries.removeAll { nowMs - it.value.latestMs > HOLD_LIFETIME_MS }
            holds.keys.toSet()
        }

    /** Forgets every hold, for tests that share this process-level registry. */
    internal fun clearForTests() {
        synchronized(UserEventNotificationGroup.mutationLock) { holds.clear() }
    }

    /** Far longer than any publish-to-post window, short enough that a lost release cannot pin a shortcut. */
    private const val HOLD_LIFETIME_MS = 60_000L
}
