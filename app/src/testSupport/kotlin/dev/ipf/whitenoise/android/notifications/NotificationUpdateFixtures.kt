package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi

/** One notification participant, defaulting to the fixture sender without a picture. */
fun notificationUser(
    accountIdHex: String = NotificationFixtureDefaults.SENDER_ACCOUNT_ID_HEX,
    displayName: String? = null,
    pictureUrl: String? = null,
) = NotificationUserFfi(accountIdHex = accountIdHex, displayName = displayName, pictureUrl = pictureUrl)

/**
 * A typed engine update with every field defaulted, so tests state only the dimension they exercise.
 * Defaults describe one group message from [NotificationFixtureDefaults.SENDER_NAME] to an account named
 * [NotificationFixtureDefaults.RECEIVER_NAME], and the keys derive from the account, group and message ids.
 */
@Suppress("LongParameterList") // Mirrors the generated update type so every field stays overridable by name.
fun notificationUpdate(
    trigger: NotificationTriggerFfi = NotificationTriggerFfi.NEW_MESSAGE,
    trafficClass: NotificationTrafficClassFfi = NotificationTrafficClassFfi.STANDARD,
    accountRef: String = NotificationFixtureDefaults.ACCOUNT_REF,
    accountIdHex: String = accountRef,
    groupIdHex: String = NotificationFixtureDefaults.GROUP_ID_HEX,
    isDm: Boolean = false,
    groupName: String? = NotificationFixtureDefaults.GROUP_NAME.takeUnless { isDm },
    isMention: Boolean = false,
    messageIdHex: String? = NotificationFixtureDefaults.MESSAGE_ID_HEX,
    notificationKey: String = "message:$accountRef:$messageIdHex",
    conversationKey: String = "conversation:$accountRef:$groupIdHex",
    sender: NotificationUserFfi = notificationUser(displayName = NotificationFixtureDefaults.SENDER_NAME),
    receiver: NotificationUserFfi =
        notificationUser(
            accountIdHex = NotificationFixtureDefaults.RECEIVER_ACCOUNT_ID_HEX,
            displayName = NotificationFixtureDefaults.RECEIVER_NAME,
        ),
    previewText: String? = NotificationFixtureDefaults.PREVIEW_TEXT,
    reactionEmoji: String? = null,
    reactedToPreview: String? = null,
    timestampMs: Long = NotificationFixtureDefaults.TIMESTAMP_MS,
    isFromSelf: Boolean = false,
) = NotificationUpdateFfi(
    notificationKey = notificationKey,
    conversationKey = conversationKey,
    trigger = trigger,
    trafficClass = trafficClass,
    accountRef = accountRef,
    accountIdHex = accountIdHex,
    groupIdHex = groupIdHex,
    groupName = groupName,
    isDm = isDm,
    isMention = isMention,
    messageIdHex = messageIdHex,
    sender = sender,
    receiver = receiver,
    previewText = previewText,
    reactionEmoji = reactionEmoji,
    reactedToPreview = reactedToPreview,
    timestampMs = timestampMs,
    isFromSelf = isFromSelf,
)

/**
 * A group invite update, which carries no message id and is keyed by the invite rather than a message.
 * The platform card for it uses [notificationKey] as its tag, so tests locate it by that value.
 */
fun groupInviteUpdate(
    accountRef: String = NotificationFixtureDefaults.ACCOUNT_REF,
    groupIdHex: String = NotificationFixtureDefaults.GROUP_ID_HEX,
    notificationKey: String = "invite:$accountRef:$groupIdHex",
    sender: NotificationUserFfi = notificationUser(displayName = NotificationFixtureDefaults.SENDER_NAME),
) = notificationUpdate(
    trigger = NotificationTriggerFfi.GROUP_INVITE,
    accountRef = accountRef,
    groupIdHex = groupIdHex,
    isDm = false,
    groupName = null,
    messageIdHex = null,
    notificationKey = notificationKey,
    sender = sender,
    previewText = null,
)
