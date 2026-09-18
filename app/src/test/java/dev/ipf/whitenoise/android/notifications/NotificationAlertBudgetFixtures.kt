package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi

/** One synthetic group message for [accountRef] in [groupIdHex], keyed uniquely by [messageIdHex]. */
internal fun alertBudgetUpdate(
    messageIdHex: String,
    timestampMs: Long,
    isMention: Boolean = false,
    accountRef: String = "account-a",
    groupIdHex: String = "group-a",
) = NotificationUpdateFfi(
    notificationKey = "key-$accountRef-$groupIdHex-$messageIdHex",
    conversationKey = "conversation-$accountRef-$groupIdHex",
    trigger = NotificationTriggerFfi.NEW_MESSAGE,
    trafficClass = NotificationTrafficClassFfi.STANDARD,
    accountRef = accountRef,
    accountIdHex = accountRef,
    groupIdHex = groupIdHex,
    groupName = "General",
    isDm = false,
    isMention = isMention,
    messageIdHex = messageIdHex,
    sender = NotificationUserFfi(accountIdHex = "01".repeat(32), displayName = "Alice", pictureUrl = null),
    receiver = NotificationUserFfi(accountIdHex = "self", displayName = "Me", pictureUrl = null),
    previewText = "hi $messageIdHex",
    reactionEmoji = null,
    reactedToPreview = null,
    timestampMs = timestampMs,
    isFromSelf = false,
)

/** True when the card was written with the flag that suppresses a repeated sound or vibration. */
internal fun Notification.isOnlyAlertOnce(): Boolean = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
