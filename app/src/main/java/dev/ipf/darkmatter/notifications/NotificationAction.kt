package dev.ipf.darkmatter.notifications

import android.content.Intent
import android.net.Uri
import dev.ipf.marmotkit.NotificationUpdateFfi

enum class NotificationActionKind { REPLY, MARK_READ }

data class NotificationActionTarget(
    val target: NotificationTarget,
    val notificationTag: String,
    val notificationId: Int,
)

data class NotificationAction(
    val kind: NotificationActionKind,
    val target: NotificationTarget,
    val notificationTag: String,
    val notificationId: Int,
)

object NotificationActions {
    const val ACTION_REPLY = "dev.ipf.darkmatter.action.REPLY_NOTIFICATION"
    const val ACTION_MARK_READ = "dev.ipf.darkmatter.action.MARK_NOTIFICATION_READ"
    const val KEY_TEXT_REPLY = "dev.ipf.darkmatter.extra.TEXT_REPLY"

    private const val EXTRA_NOTIFICATION_TAG = "dev.ipf.darkmatter.extra.NOTIFICATION_TAG"
    private const val EXTRA_NOTIFICATION_ID = "dev.ipf.darkmatter.extra.NOTIFICATION_ID"
    private const val URI_SCHEME = "darkmatter-notify"

    fun targetFromUpdate(
        update: NotificationUpdateFfi,
        notificationTag: String,
        notificationId: Int,
    ): NotificationActionTarget? {
        val target = NotificationNavigation.fromUpdate(update) ?: return null
        if (target.kind != NotificationTargetKind.MESSAGE) return null
        if (target.messageIdHex.isNullOrBlank()) return null
        val tag = notificationTag.takeIf { it.isNotBlank() } ?: return null
        return NotificationActionTarget(target, tag, notificationId)
    }

    fun actionUriString(
        kind: NotificationActionKind,
        notificationTag: String,
    ): String = "$URI_SCHEME://action/${kind.name.lowercase()}/" + notificationTag.ifBlank { "unknown" }

    fun requestCode(
        kind: NotificationActionKind,
        notificationTag: String,
    ): Int = 31 * notificationTag.hashCode() + kind.name.hashCode()

    fun applyToIntent(
        intent: Intent,
        kind: NotificationActionKind,
        actionTarget: NotificationActionTarget,
    ) {
        intent.action = actionName(kind)
        intent.data = Uri.parse(actionUriString(kind, actionTarget.notificationTag))
        NotificationNavigation.applyTargetExtras(intent, actionTarget.target)
        intent.putExtra(EXTRA_NOTIFICATION_TAG, actionTarget.notificationTag)
        intent.putExtra(EXTRA_NOTIFICATION_ID, actionTarget.notificationId)
    }

    fun parse(intent: Intent?): NotificationAction? {
        intent ?: return null
        val actionKind = kindForAction(intent.action) ?: return null
        val target = NotificationNavigation.parseTarget(intent) ?: return null
        return parseFields(
            actionKind = actionKind,
            target = target,
            notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG),
            notificationId =
                if (intent.hasExtra(EXTRA_NOTIFICATION_ID)) {
                    intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                } else {
                    null
                },
        )
    }

    fun parseFields(
        action: String?,
        accountRef: String?,
        groupIdHex: String?,
        messageIdHex: String?,
        targetKindName: String?,
        notificationTag: String?,
        notificationId: Int?,
    ): NotificationAction? {
        val actionKind = kindForAction(action) ?: return null
        val target = NotificationNavigation.parseTargetExtras(accountRef, groupIdHex, messageIdHex, targetKindName) ?: return null
        return parseFields(actionKind, target, notificationTag, notificationId)
    }

    private fun parseFields(
        actionKind: NotificationActionKind,
        target: NotificationTarget,
        notificationTag: String?,
        notificationId: Int?,
    ): NotificationAction? {
        if (target.kind != NotificationTargetKind.MESSAGE) return null
        if (target.messageIdHex.isNullOrBlank()) return null
        val tag = notificationTag?.takeIf { it.isNotBlank() } ?: return null
        val id = notificationId ?: return null
        return NotificationAction(actionKind, target, tag, id)
    }

    private fun actionName(kind: NotificationActionKind): String =
        when (kind) {
            NotificationActionKind.REPLY -> ACTION_REPLY
            NotificationActionKind.MARK_READ -> ACTION_MARK_READ
        }

    private fun kindForAction(action: String?): NotificationActionKind? =
        when (action) {
            ACTION_REPLY -> NotificationActionKind.REPLY
            ACTION_MARK_READ -> NotificationActionKind.MARK_READ
            else -> null
        }
}
