package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter.GROUP_MEMBERSHIP_NOTIFICATION_ID
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID
import java.security.MessageDigest
import java.util.UUID

/** An OS card generation, not a protocol message or an unread record. */
internal data class NotificationGroupChild(
    val tag: String,
    val id: Int,
    val generation: String,
)

/** Frozen Android group/summary identities shared by every account and user-event category. */
internal object UserEventNotificationGroup {
    const val KEY = "whitenoise_user_events_v1"
    const val SUMMARY_TAG = "whitenoise_user_event_summary_v1"
    const val SUMMARY_ID = 9200
    const val EXTRA_GENERATION = "dev.ipf.whitenoise.notify.card_generation"
    const val EXTRA_CHILD = "dev.ipf.whitenoise.notify.user_event_child"
    const val EXTRA_LEGACY_POST_TIME = "dev.ipf.whitenoise.notify.legacy_post_time"
    const val EXTRA_SUMMARY_STATE = "dev.ipf.whitenoise.notify.group_summary_state"
    const val ACTION_DISMISS = "dev.ipf.whitenoise.notify.DISMISS_GROUP_GENERATIONS"
    const val EXTRA_TAGS = "tags"
    const val EXTRA_IDS = "ids"
    const val EXTRA_GENERATIONS = "generations"
    private const val SUMMARY_OPEN_REQUEST = 9201
    private const val MAX_CHILDREN = 128

    // Card stripes must be acquired before this gate. Summary commits acquire only this gate.
    // It serializes short platform reads/writes; pacing, disk/native reads and suspension stay outside.
    val mutationLock = Any()

    fun decorateChild(
        context: Context,
        builder: NotificationCompat.Builder,
        child: NotificationGroupChild,
        silent: Boolean,
    ): NotificationCompat.Builder =
        builder
            .setGroup(KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSortKey(attentionSortKey(child.id, silent))
            .addExtras(
                Bundle().apply {
                    putBoolean(EXTRA_CHILD, true)
                    putString(EXTRA_GENERATION, child.generation)
                },
            ).setDeleteIntent(deleteIntent(context, listOf(child), child.generation))

    fun attentionSortKey(
        id: Int,
        silent: Boolean,
    ): String =
        when {
            id == LocalNotificationFormatter.MENTION_NOTIFICATION_ID -> "0"
            silent || id == LocalNotificationFormatter.AGENT_ACTIVITY_NOTIFICATION_ID -> "2"
            else -> "1"
        }

    fun child(notification: StatusBarNotification): NotificationGroupChild? {
        val card = notification.notification
        if (!isChildCandidate(notification) || card.group != KEY || isSummary(card)) return null
        return card.extras.getString(EXTRA_GENERATION)?.takeIf(String::isNotBlank)?.let { generation ->
            NotificationGroupChild(requireNotNull(notification.tag), notification.id, generation)
        }
    }

    /** Only known presenter cards can be adopted; services, updates and unrelated notifications are excluded. */
    fun isChildCandidate(notification: StatusBarNotification): Boolean {
        val tag = notification.tag?.takeIf(String::isNotBlank) ?: return false
        val validId = notification.id in MESSAGE_NOTIFICATION_ID..GROUP_MEMBERSHIP_NOTIFICATION_ID
        val extras = notification.notification.extras
        val identifiable =
            extras.getBoolean(EXTRA_CHILD) || isLegacyInvitation(notification) || matchesLegacyTag(tag, notification.id)
        return validId && identifiable
    }

    fun isSummary(notification: Notification): Boolean = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** Adoption/cosmetic writes must not turn an old OS card into a later message for UI cleanup. */
    fun dismissalTime(notification: StatusBarNotification): Long {
        if (!isChildCandidate(notification)) return notification.postTime
        val original = notification.notification.extras.getLong(EXTRA_LEGACY_POST_TIME, notification.postTime)
        return original.takeIf { it > 0L && it <= notification.postTime } ?: notification.postTime
    }

    fun summaryState(children: List<NotificationGroupChild>): String {
        val bytes =
            children
                .sortedWith(compareBy({ it.tag }, { it.id }))
                .joinToString("\n") { "${it.tag}\u0000${it.id}\u0000${it.generation}" }
                .toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun summary(
        context: Context,
        children: List<NotificationGroupChild>,
    ): Notification {
        require(children.isNotEmpty() && children.size <= MAX_CHILDREN)
        val state = summaryState(children)
        val publicVersion = redactedGroupSummary(context)
        return NotificationCompat
            .Builder(context, NotificationChannelSpec.USER_EVENT_SUMMARY.id)
            .setSmallIcon(R.drawable.ic_stat_whitenoise)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                context.resources.getQuantityString(R.plurals.notification_group_count, children.size, children.size),
            )
            .setGroup(KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    SUMMARY_OPEN_REQUEST,
                    Intent(context, MainActivity::class.java)
                        .setAction("${context.packageName}.OPEN_NOTIFICATION_GROUP"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).setDeleteIntent(deleteIntent(context, children, state))
            .addExtras(Bundle().apply { putString(EXTRA_SUMMARY_STATE, state) })
            .build()
    }

    private fun deleteIntent(
        context: Context,
        children: List<NotificationGroupChild>,
        identity: String = UUID.randomUUID().toString(),
    ): PendingIntent {
        val intent =
            Intent(context, NotificationGroupDismissReceiver::class.java)
                .setAction(ACTION_DISMISS)
                .setData(
                    Uri
                        .Builder()
                        .scheme("whitenoise-notification")
                        .authority("dismiss")
                        .appendPath(identity)
                        .build(),
                ).putStringArrayListExtra(EXTRA_TAGS, ArrayList(children.map { it.tag }))
                .putExtra(EXTRA_IDS, children.map { it.id }.toIntArray())
                .putStringArrayListExtra(EXTRA_GENERATIONS, ArrayList(children.map { it.generation }))
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("ReturnCount") // Reject malformed immutable callback payloads before scheduling any work.
    fun dismissalChildren(intent: Intent): List<NotificationGroupChild>? {
        if (intent.action != ACTION_DISMISS) return null
        val tags = intent.getStringArrayListExtra(EXTRA_TAGS) ?: return null
        val ids = intent.getIntArrayExtra(EXTRA_IDS) ?: return null
        val generations = intent.getStringArrayListExtra(EXTRA_GENERATIONS) ?: return null
        if (tags.isEmpty() || tags.size > MAX_CHILDREN) return null
        if (tags.size != ids.size || tags.size != generations.size) return null
        if (tags.any(String::isBlank) || generations.any(String::isBlank)) return null
        if (ids.any { it !in MESSAGE_NOTIFICATION_ID..GROUP_MEMBERSHIP_NOTIFICATION_ID }) return null
        return tags.indices.map { NotificationGroupChild(tags[it], ids[it], generations[it]) }
    }
}

private fun isLegacyInvitation(notification: StatusBarNotification): Boolean {
    val extras = notification.notification.extras
    return notification.id == MESSAGE_NOTIFICATION_ID &&
        !extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF).isNullOrBlank() &&
        !extras.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID).isNullOrBlank()
}

private fun matchesLegacyTag(
    tag: String,
    id: Int,
): Boolean {
    val prefix =
        when (id) {
            LocalNotificationFormatter.REACTION_NOTIFICATION_ID -> "reaction|"
            LocalNotificationFormatter.MENTION_NOTIFICATION_ID -> "mention|"
            LocalNotificationFormatter.AGENT_ACTIVITY_NOTIFICATION_ID -> "agent-activity|"
            GROUP_MEMBERSHIP_NOTIFICATION_ID -> "group-membership|"
            else -> ""
        }
    if (!tag.startsWith(prefix)) return false
    val unscopedTag = tag.removePrefix(prefix)
    val recipient = unscopedTag.substringBefore('|')
    return unscopedTag.contains('|') &&
        unscopedTag.substringAfter('|').isNotBlank() &&
        LocalNotificationFormatter.deterministicTagBelongsToAccount(tag, recipient)
}

private fun redactedGroupSummary(context: Context): Notification =
    NotificationCompat
        .Builder(context, NotificationChannelSpec.USER_EVENT_SUMMARY.id)
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_hidden_content))
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setSilent(true)
        .build()
