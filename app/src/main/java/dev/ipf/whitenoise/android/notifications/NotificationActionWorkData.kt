package dev.ipf.whitenoise.android.notifications

import androidx.work.Data
import androidx.work.workDataOf

internal object NotificationActionWorkData {
    private const val KEY_ACTION = "action"
    private const val KEY_ACCOUNT_REF = "account_ref"
    private const val KEY_GROUP_ID_HEX = "group_id_hex"
    private const val KEY_MESSAGE_ID_HEX = "message_id_hex"
    private const val KEY_TARGET_KIND = "target_kind"
    private const val KEY_NOTIFICATION_TAG = "notification_tag"
    private const val KEY_NOTIFICATION_ID = "notification_id"

    fun encode(action: NotificationAction): Data =
        workDataOf(
            KEY_ACTION to
                when (action.kind) {
                    NotificationActionKind.REPLY -> NotificationActions.ACTION_REPLY
                    NotificationActionKind.MARK_READ -> NotificationActions.ACTION_MARK_READ
                },
            KEY_ACCOUNT_REF to action.target.accountRef,
            KEY_GROUP_ID_HEX to action.target.groupIdHex,
            KEY_MESSAGE_ID_HEX to action.target.messageIdHex.orEmpty(),
            KEY_TARGET_KIND to action.target.kind.name,
            KEY_NOTIFICATION_TAG to action.notificationTag,
            KEY_NOTIFICATION_ID to action.notificationId,
        )

    fun decode(data: Data): NotificationAction? =
        NotificationActions.parseRawFields(
            action = data.getString(KEY_ACTION),
            accountRef = data.getString(KEY_ACCOUNT_REF),
            groupIdHex = data.getString(KEY_GROUP_ID_HEX),
            messageIdHex = data.getString(KEY_MESSAGE_ID_HEX),
            targetKindName = data.getString(KEY_TARGET_KIND),
            notificationTag = data.getString(KEY_NOTIFICATION_TAG),
            notificationId = data.getInt(KEY_NOTIFICATION_ID, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE },
        )
}
