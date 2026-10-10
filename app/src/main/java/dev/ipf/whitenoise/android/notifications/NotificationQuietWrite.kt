package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import androidx.core.app.NotificationCompat

/**
 * Whether this child card was built with `setSilent(true)`.
 *
 * AndroidX applies `setSilent` at build time by moving a non-summary card to GROUP_ALERT_SUMMARY and clearing
 * its sound and vibration, so that group behavior on a child is the only trace left on the built card.
 * Presenter cards always carry GROUP_ALERT_CHILDREN unless they were silenced, which keeps this unambiguous.
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
 * replaces the current message has never set it, and that stays as it was. The decision belongs at the final
 * write, where [liveCard] is read under the card lock. A swipe landing between that read and the platform post
 * can still let one write ring, a window of milliseconds that no flag choice closes.
 */
internal fun NotificationCompat.Builder.quietWrite(
    replaceCurrentMessage: Boolean,
    notificationId: Int,
    liveCard: Notification?,
): NotificationCompat.Builder {
    val keepsHeadsUp = liveCard != null && !liveCard.wasBuiltSilent()
    return setOnlyAlertOnce(true)
        .setSortKey(
            liveCard?.sortKey?.takeIf(String::isNotEmpty)
                ?: UserEventNotificationGroup.attentionSortKey(notificationId, silent = true),
        ).setSilent(!keepsHeadsUp && !replaceCurrentMessage)
}
