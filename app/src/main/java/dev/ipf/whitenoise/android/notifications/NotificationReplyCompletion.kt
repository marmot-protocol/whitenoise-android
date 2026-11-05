package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val REPLY_LOG_TAG_LENGTH = 16

/** Completes SystemUI RemoteInput lifetimes while preserving the live card's generation. */
internal class NotificationReplyCompletion(
    private val context: Context,
    private val publisher: NotificationCardPublisher,
) {
    /**
     * Re-post the (tag, id) notification carrying a RemoteInput history entry —
     * the documented "reply handled" signal that clears the system's
     * FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY. A direct-reply notification is
     * lifetime-extended by the system (API 34+) and a bare [LocalNotificationPresenter.cancel] is ignored
     * while that flag is set, so the caller must do this (and let it settle)
     * before cancelling. Returns true once the live notification was found and
     * re-posted; false if it isn't in the active set yet (caller should retry —
     * the extension is applied a beat after the reply broadcast fires).
     *
     * The re-post is rebuilt FROM the live notification (recovering its content
     * intent, MessagingStyle, category, and reply/mark-read actions) with only
     * the RemoteInput history added on top — so if the follow-up cancel is
     * dropped or delayed, the card that survives is still the functional
     * conversation card, not a blank tap-dead one.
     */
    @SuppressLint("MissingPermission")
    fun markDirectReplyHandled(
        notificationTag: String,
        notificationId: Int,
        replyText: String,
    ): Boolean = markRemoteInputHandled(notificationTag, notificationId, expectedMessageIdHex = null, replyText)

    /**
     * Clear a reaction chip's direct-reply lifetime extension only while the
     * live card still represents the message that offered the chip. A newer
     * message reuses the same notification key and must not inherit the old
     * reaction as RemoteInput history.
     */
    internal fun markReactionHandledIfSameGeneration(
        notificationTag: String,
        notificationId: Int,
        reactedMessageIdHex: String?,
        reaction: String,
    ): Boolean = markRemoteInputHandled(notificationTag, notificationId, reactedMessageIdHex, reaction)

    /**
     * Re-posts the live (tag, id) card with [handledText] as its RemoteInput history, ending
     * SystemUI's reply spinner. Skipped when [expectedMessageIdHex] no longer matches the live card.
     */
    @SuppressLint("MissingPermission")
    private fun markRemoteInputHandled(
        notificationTag: String,
        notificationId: Int,
        expectedMessageIdHex: String?,
        handledText: String,
    ): Boolean =
        ConversationCardPostSynchronizer.withLock(
            notificationTag,
            notificationId,
            ConversationCardOp.MARK_REPLY_HANDLED,
        ) {
            val active =
                runCatching {
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.activeNotifications
                        ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
                }.getOrNull() ?: return@withLock false
            if (
                expectedMessageIdHex != null &&
                !shouldCancelRepliedConversationCard(
                    expectedMessageIdHex,
                    conversationCardMessageIdHex(active.notification),
                )
            ) {
                return@withLock false
            }
            ConversationCardPostSynchronizer.awaitTestBarrier(
                ConversationCardOp.MARK_REPLY_HANDLED,
                ConversationCardBarrier.AFTER_READ,
                notificationTag,
                notificationId,
            )
            runCatching {
                val resolved =
                    NotificationCompat
                        .Builder(context, active.notification)
                        .apply {
                            extras.remove(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                            extras.remove(EXTRA_REMOTE_INPUT_HISTORY_ITEMS)
                        }.setRemoteInputHistory(arrayOf(handledText))
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .build()
                        .withoutApi30RemoteInputHistoryItems()
                if (publisher.postNotificationSafely(
                        NotificationManagerCompat.from(context),
                        notificationTag,
                        notificationId,
                        resolved,
                        mustBeLive = true,
                    ) !=
                    NotificationCardWriteResult.WRITTEN
                ) {
                    return@runCatching false
                }
                notificationDebug {
                    "remote-input-handled re-post tag=${notificationTag.take(REPLY_LOG_TAG_LENGTH)} id=$notificationId"
                }
                true
            }.getOrDefault(false)
        }

    /**
     * Re-post the (tag, id) conversation card after a direct reply failed
     * terminally, clearing the direct-reply lifetime extension the same way
     * [markDirectReplyHandled] does — but stamping [failureNotice] into the
     * RemoteInput history instead of the reply text, so the card reads as
     * not-sent while keeping its original actions (the user can tap Reply and
     * retype). Skipped when the live card is a newer generation than the
     * replied message: that newer post already cleared the extension, and a
     * failure line rendered over newer messages would mislabel them.
     */
    @SuppressLint("MissingPermission")
    fun markDirectReplyFailed(
        notificationTag: String,
        notificationId: Int,
        repliedMessageIdHex: String?,
        failureNotice: String,
    ): Boolean =
        ConversationCardPostSynchronizer.withLock(
            notificationTag,
            notificationId,
            ConversationCardOp.MARK_REPLY_FAILED,
        ) {
            val active =
                runCatching {
                    context
                        .getSystemService(NotificationManager::class.java)
                        ?.activeNotifications
                        ?.firstOrNull { it.tag == notificationTag && it.id == notificationId }
                }.getOrNull() ?: return@withLock false
            val liveCardMessageIdHex = conversationCardMessageIdHex(active.notification)
            if (!shouldCancelRepliedConversationCard(repliedMessageIdHex, liveCardMessageIdHex)) {
                return@withLock false
            }
            runCatching {
                val resolved =
                    NotificationCompat
                        .Builder(context, active.notification)
                        .apply {
                            extras.remove(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                            extras.remove(EXTRA_REMOTE_INPUT_HISTORY_ITEMS)
                        }.setRemoteInputHistory(arrayOf(failureNotice))
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .build()
                        .withoutApi30RemoteInputHistoryItems()
                if (publisher.postNotificationSafely(
                        NotificationManagerCompat.from(context),
                        notificationTag,
                        notificationId,
                        resolved,
                        mustBeLive = true,
                    ) !=
                    NotificationCardWriteResult.WRITTEN
                ) {
                    return@runCatching false
                }
                notificationDebug {
                    "reply-failed re-post tag=${notificationTag.take(REPLY_LOG_TAG_LENGTH)} id=$notificationId"
                }
                true
            }.getOrDefault(false)
        }
}
