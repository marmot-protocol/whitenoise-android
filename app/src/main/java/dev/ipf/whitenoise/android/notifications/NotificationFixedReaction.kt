package dev.ipf.whitenoise.android.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Ephemeral card routing is copied by rich rebuilds and omitted by the generic privacy rebuild. */
internal fun notificationFixedReactionTargetExtras(target: NotificationActionTarget): Bundle =
    Bundle().apply {
        val intent = Intent()
        NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, target)
        putBundle(EXTRA_FIXED_REACTION_TARGET, intent.extras)
    }

/** Fixed choices never share the mutable RemoteInput identity or rely on a hash to distinguish targets. */
internal fun fixedNotificationReactionIntent(
    context: Context,
    target: NotificationActionTarget,
    choice: String,
    allowedChoices: List<String>,
): Intent? {
    val normalized = normalizeNotificationReaction(choice)?.takeIf { it in allowedChoices } ?: return null
    val intent = Intent(context, NotificationActionReceiver::class.java)
    NotificationActions.applyToIntent(intent, NotificationActionKind.REACT, target)
    return NotificationActions.parse(intent)?.let { parsed ->
        intent.data =
            Uri
                .Builder()
                .scheme("whitenoise-notify")
                .authority("fixed-reaction")
                .appendPath(parsed.target.accountRef)
                .appendPath(parsed.target.groupIdHex)
                .appendPath(checkNotNull(parsed.target.messageIdHex))
                .appendPath(parsed.target.kind.name)
                .appendPath(parsed.notificationTag)
                .appendPath(parsed.notificationId.toString())
                .appendPath(normalized)
                .build()
        intent.putExtra(NotificationActions.LEGACY_EXTRA_REACTION, normalized)
        intent
    }
}

internal fun fixedNotificationReactionPendingIntent(
    context: Context,
    target: NotificationActionTarget,
    choice: String,
    allowedChoices: List<String>,
): PendingIntent? =
    fixedNotificationReactionIntent(context, target, choice, allowedChoices)?.let { intent ->
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

internal const val EXTRA_FIXED_REACTION_TARGET = "dev.ipf.whitenoise.android.notification.FIXED_REACTION_TARGET"
