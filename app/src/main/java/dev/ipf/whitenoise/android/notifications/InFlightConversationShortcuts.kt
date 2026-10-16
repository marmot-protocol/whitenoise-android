package dev.ipf.whitenoise.android.notifications

import android.os.SystemClock
import androidx.core.content.pm.ShortcutInfoCompat

/**
 * Conversation shortcuts the presenter has published for a card it has not posted yet. The shortcut is published
 * under [UserEventNotificationGroup.mutationLock] before the card's platform write, which retakes the lock later, and
 * a Direct Share refresh that replaces the dynamic set in between sees no live card for it. Holding the id here keeps
 * that refresh from deleting the shortcut, so the card never names a missing shortcut. Posts for one conversation can
 * overlap under one shortcut id, so holds are counted and the id stays held until the last one is released. A hold that
 * is never released lapses [HOLD_LIFETIME_MS] after the latest hold.
 *
 * The platform's shortcut readback carries no icon, so a refresh cannot rebuild a held shortcut's picture from it. A
 * hold therefore keeps the shortcut the presenter published, which the refresh retains as published. A refresh that had
 * to rebuild a card's shortcut without its picture marks it [markIdentityLost], so the presenter publishes it again.
 */
internal object InFlightConversationShortcuts {
    /** How many pending posts hold one shortcut id, and when the latest of them took its hold. */
    private data class Hold(
        val count: Int,
        val latestMs: Long,
    )

    private val holds = HashMap<String, Hold>()
    private val published = HashMap<String, ShortcutInfoCompat>()
    private val identityLost = HashSet<String>()

    /**
     * Adds one hold on [shortcutId] for a pending post, keeping [publishedShortcut] when the presenter has just
     * published it. The caller already holds the shared publication lock.
     */
    fun hold(
        shortcutId: String,
        publishedShortcut: ShortcutInfoCompat? = null,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            holds[shortcutId] = Hold((holds[shortcutId]?.count ?: 0) + 1, nowMs)
            publishedShortcut?.let { published[shortcutId] = it }
        }
    }

    /** Releases one hold on [shortcutId] once its post has been written or abandoned, freeing it after the last. */
    fun release(shortcutId: String) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            val held = holds[shortcutId] ?: return
            if (held.count <= 1) {
                holds.remove(shortcutId)
                published.remove(shortcutId)
            } else {
                holds[shortcutId] = held.copy(count = held.count - 1)
            }
        }
    }

    /** The shortcut the presenter published for a held [shortcutId], picture included, or null. */
    fun publishedShortcut(shortcutId: String): ShortcutInfoCompat? =
        synchronized(UserEventNotificationGroup.mutationLock) { published[shortcutId]?.takeIf { shortcutId in holds } }

    /** Records that a refresh rebuilt [shortcutId] without the picture the presenter published. */
    fun markIdentityLost(shortcutId: String) {
        synchronized(UserEventNotificationGroup.mutationLock) { identityLost += shortcutId }
    }

    /** Whether a refresh replaced [shortcutId]'s published picture since the last check, clearing the mark. */
    fun consumeIdentityLost(shortcutId: String): Boolean {
        synchronized(UserEventNotificationGroup.mutationLock) {
            return identityLost.remove(shortcutId)
        }
    }

    /** Ids still held for a pending post, dropping any whose latest hold has lapsed. */
    fun ids(nowMs: Long = SystemClock.elapsedRealtime()): Set<String> =
        synchronized(UserEventNotificationGroup.mutationLock) {
            holds.entries.removeAll { nowMs - it.value.latestMs > HOLD_LIFETIME_MS }
            published.keys.retainAll(holds.keys)
            holds.keys.toSet()
        }

    /** Forgets every hold, for tests that share this process-level registry. */
    internal fun clearForTests() {
        synchronized(UserEventNotificationGroup.mutationLock) {
            holds.clear()
            published.clear()
            identityLost.clear()
        }
    }

    /** Far longer than any publish-to-post window, short enough that a lost release cannot pin a shortcut. */
    private const val HOLD_LIFETIME_MS = 60_000L
}
