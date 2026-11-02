package dev.ipf.whitenoise.android.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R

/** Builds notification actions and navigation intents using the presenter's existing tap-token owner. */
internal class NotificationInteractionFactory(
    private val context: Context,
    private val tapTokens: NotificationTapTokens,
) {
    fun conversationShortcutOpenIntent(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
    ): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            NotificationNavigation.fromUpdate(update)?.let { target ->
                NotificationNavigation.applyToIntent(
                    this,
                    target,
                    content.notificationTag,
                    tapTokens.tokenFor(content.notificationTag),
                )
            }
        }

    fun replyNotificationAction(
        actionTarget: NotificationActionTarget,
        reactionChoices: List<String>,
    ): NotificationCompat.Action {
        val remoteInput =
            RemoteInput
                .Builder(NotificationActions.KEY_TEXT_REPLY)
                .setLabel(context.getString(R.string.message))
                .setChoices(reactionChoices.toTypedArray())
                .setAllowFreeFormInput(true)
                .setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED)
                .build()
        return NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_whitenoise,
                context.getString(R.string.reply),
                actionPendingIntent(
                    actionTarget,
                    NotificationActionKind.REPLY,
                    acceptsInlineReactionChoices = reactionChoices.isNotEmpty(),
                ),
            ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    fun markReadNotificationAction(actionTarget: NotificationActionTarget): NotificationCompat.Action =
        NotificationCompat
            .Action
            .Builder(
                R.drawable.ic_stat_whitenoise,
                context.getString(R.string.chat_row_action_mark_read),
                actionPendingIntent(actionTarget, NotificationActionKind.MARK_READ),
            ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun actionPendingIntent(
        actionTarget: NotificationActionTarget,
        kind: NotificationActionKind,
        acceptsInlineReactionChoices: Boolean = false,
    ): PendingIntent {
        val actionIntent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                NotificationActions.applyToIntent(
                    this,
                    kind,
                    actionTarget,
                    acceptsInlineReactionChoices,
                )
            }
        val mutableFlag =
            if (kind == NotificationActionKind.REPLY || kind == NotificationActionKind.REACT) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
        return PendingIntent.getBroadcast(
            context,
            NotificationActions.requestCode(kind, actionTarget.notificationTag),
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    fun conversationPendingIntent(
        update: NotificationUpdateFfi,
        tag: String,
    ): PendingIntent {
        val acceptsDraft = update.trigger == NotificationTriggerFfi.NEW_MESSAGE && update.reactionEmoji.isNullOrBlank()
        val target = NotificationNavigation.fromUpdate(update)
        val signature = target?.takeIf { acceptsDraft }?.let { tapTokens.signatureFor(tag, it) }
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Key the tap target on the notification tag (per-conversation
                // for messages) so the accumulating card always reopens the
                // same conversation. PendingIntents compare by URI, not extras.
                target?.let { destination ->
                    if (signature != null) {
                        NotificationNavigation.applyBoundToIntent(this, destination, tag, signature)
                    } else {
                        NotificationNavigation.applyToIntent(this, destination, tag, tapTokens.tokenFor(tag))
                    }
                }
            }
        return PendingIntent.getActivity(
            context,
            NotificationNavigation.requestCode(tag),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (signature != null) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
