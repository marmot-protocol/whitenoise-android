package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import androidx.core.app.NotificationCompat

/**
 * Whether this child card was built with `setSilent(true)`.
 *
 * AndroidX applies `setSilent` at build time by moving a non-summary card to GROUP_ALERT_SUMMARY and clearing
 * its sound and vibration, so that group behavior on a child is the only trace left on the built card.
 * A presenter child card otherwise carries GROUP_ALERT_CHILDREN, or GROUP_ALERT_ALL when a contact rewrite
 * rebuilt it from a recovered builder, so SUMMARY on a child is unambiguous.
 */
internal fun Notification.wasBuiltSilent(): Boolean =
    groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY &&
        flags and Notification.FLAG_GROUP_SUMMARY == 0

/**
 * Marks one write as quiet without withdrawing a heads-up banner that the same key is already showing.
 *
 * `setSilent(true)` moves a child card to GROUP_ALERT_SUMMARY, and SystemUI stops treating a card with that
 * behavior as eligible for heads-up, so it removes a banner that is still up for the key. For an update of a
 * posted card `FLAG_ONLY_ALERT_ONCE` alone already stops sound, vibration and a repeat banner, so a write over
 * a [liveCard] that can still show a banner sets only that flag and copies the live sort key, leaving the
 * card's group behavior and rank as they are. A live card that was itself built silent never showed a banner,
 * so it stays silent rather than becoming newly eligible to show one.
 *
 * With no live card nothing would honour `FLAG_ONLY_ALERT_ONCE`, since the platform only mutes updates, so the
 * write keeps `setSilent` and a card the user already swiped away cannot ring a second time. A write that
 * replaces the current message has never set it when there is no live card. Over a live card that was built
 * silent, the write stays silent whether or not it replaces the message, because that card never alerted and
 * a late correction must not make it newly eligible to. The decision belongs at the final write, where
 * [liveCard] is read under the card lock. A swipe landing between that read and the platform post can still
 * let one write ring, a window of milliseconds that no flag choice closes. A caller that has just cancelled
 * the card itself passes no [liveCard], since the platform applies a cancel asynchronously and the listed card
 * is about to disappear.
 */
internal fun NotificationCompat.Builder.quietWrite(
    replaceCurrentMessage: Boolean,
    notificationId: Int,
    liveCard: Notification?,
): NotificationCompat.Builder {
    val silent = if (liveCard != null) liveCard.wasBuiltSilent() else !replaceCurrentMessage
    return setOnlyAlertOnce(true)
        .setSortKey(
            liveCard?.sortKey?.takeIf(String::isNotEmpty)
                ?: UserEventNotificationGroup.attentionSortKey(notificationId, silent = true),
        ).setSilent(silent)
}
