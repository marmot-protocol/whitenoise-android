package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Per-conversation notification channels.
 *
 * A conversation channel is a child of a parent [NotificationChannelSpec]
 * channel, linked via [NotificationChannel.setConversationId]. Creating one is
 * what lets Android expose per-conversation sound / vibration / importance
 * controls: the "Customize sound & vibration" deep link resolves to it, and a
 * posted notification that carries the matching shortcut id + channel id is
 * treated as a conversation (People section, per-conversation settings). Without
 * it Android silently falls back to the app-wide parent channel.
 *
 * A single conversation can receive traffic on more than one parent — ordinary
 * messages on its DM/group channel, mentions, reactions, invites, and agent
 * activity — so a conversation channel is created per relevant parent. The channel id is derived
 * deterministically from (parent id, conversation shortcut id) so both the post
 * path and the settings deep link name the same channel.
 *
 * Conversation channels require API 30; the app's minSdk is above that, so the
 * APIs are always available here.
 */
object ConversationNotificationChannels {
    private const val CONVERSATION_CHANNEL_INFIX = "conv"

    /**
     * Stable id for the conversation child of [parentChannelId]. Derived, not
     * stored, so it is reproducible without any lookup. The shortcut id is
     * already a bounded hash (see [conversationShortcutId]), so the composite
     * stays well within the OS channel-id length limit.
     */
    fun conversationChannelId(
        parentChannelId: String,
        conversationShortcutId: String,
    ): String = "$parentChannelId:$CONVERSATION_CHANNEL_INFIX:$conversationShortcutId"

    /**
     * The default parent for callers that want the conversation's ordinary
     * message settings. Typed settings rows pass their parent explicitly.
     */
    fun primaryMessageParent(isDm: Boolean): NotificationChannelSpec =
        if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES

    /**
     * Every notification type that can be scoped to a conversation. Keeping this
     * matrix complete lets each chat expose independent native sound, vibration,
     * and importance controls for each type.
     */
    fun relevantParents(isDm: Boolean): List<NotificationChannelSpec> =
        listOf(
            primaryMessageParent(isDm),
            NotificationChannelSpec.MENTIONS,
            NotificationChannelSpec.REACTIONS,
            NotificationChannelSpec.INVITES,
            NotificationChannelSpec.AGENT_ACTIVITY,
        )

    /** Creates the conversation channel for every parent this conversation can receive on. */
    fun ensureConversationChannels(
        context: Context,
        conversationShortcutId: String,
        isDm: Boolean,
        conversationTitle: String? = null,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        relevantParents(isDm).forEach { parent ->
            ensureConversationChannel(manager, parent.id, conversationShortcutId, conversationTitle)
        }
    }

    /**
     * Ensures the conversation channel for a single [parentChannelId] exists,
     * cloning the parent's alerting settings on first creation, and returns its
     * id. Returns null when the parent channel is missing (nothing to clone).
     */
    fun ensureConversationChannel(
        context: Context,
        parentChannelId: String,
        conversationShortcutId: String,
        conversationTitle: String? = null,
    ): String? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return ensureConversationChannel(manager, parentChannelId, conversationShortcutId, conversationTitle)
    }

    private fun ensureConversationChannel(
        manager: NotificationManager,
        parentChannelId: String,
        conversationShortcutId: String,
        conversationTitle: String?,
    ): String? {
        val conversationChannelId = conversationChannelId(parentChannelId, conversationShortcutId)
        val parent = manager.getNotificationChannel(parentChannelId) ?: return null
        val displayName = conversationChannelDisplayName(parent.name, conversationTitle)
        val existing = manager.getNotificationChannel(conversationChannelId)
        if (existing != null) {
            var republish = false
            // Android permits an app to refresh a channel's user-visible name
            // while retaining every user-controlled alerting override. This
            // upgrades channels created before profile/group metadata resolved.
            // A title-less notification post must not undo that upgrade after
            // a process restart.
            if (
                !conversationTitle.isNullOrBlank() &&
                existing.name.toString() != displayName.toString()
            ) {
                existing.name = displayName
                republish = true
            }
            if (shouldDowngradeImportance(existing, parent)) {
                existing.importance = parent.importance
                republish = true
            }
            if (republish) manager.createNotificationChannel(existing)
            return conversationChannelId
        }
        manager.createNotificationChannel(
            conversationChannel(parent, conversationChannelId, conversationShortcutId, displayName),
        )
        return conversationChannelId
    }

    /**
     * Android only re-applies importance on an existing channel when the new
     * value is strictly lower and the user has never set it themselves; every
     * other field of the update is discarded. Mirroring that rule here keeps a
     * lowered parent default propagating to untouched conversation children
     * while a customised child is left exactly as the user left it.
     */
    internal fun shouldDowngradeImportance(
        existing: NotificationChannel,
        parent: NotificationChannel,
    ): Boolean = !existing.hasUserSetImportance() && parent.importance < existing.importance

    /** Live per-conversation alerting state, read back for display. */
    data class ConversationChannelStatus(
        val importance: Int,
        val userSetImportance: Boolean,
        val importantConversation: Boolean,
    )

    /**
     * Reads the conversation child's live state, falling back to the parent
     * when the child has not been created yet — an untouched conversation
     * alerts exactly like its parent, so that is the honest reading.
     */
    fun conversationChannelStatus(
        context: Context,
        parentChannelId: String,
        conversationShortcutId: String,
    ): ConversationChannelStatus? {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = conversationChannelId(parentChannelId, conversationShortcutId)
        val channel =
            manager?.getNotificationChannel(channelId)
                ?: manager?.getNotificationChannel(parentChannelId)
        return channel?.let {
            ConversationChannelStatus(
                importance = it.importance,
                userSetImportance = it.hasUserSetImportance(),
                importantConversation = it.isImportantConversation,
            )
        }
    }

    internal fun conversationChannelDisplayName(
        parentName: CharSequence,
        conversationTitle: String?,
    ): CharSequence =
        conversationTitle
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { "$it · $parentName" }
            ?: parentName

    // Clone the parent's importance and alerting defaults onto the conversation
    // channel at creation time; the user can then diverge per conversation from
    // the OS settings without affecting the parent or its other conversations.
    private fun conversationChannel(
        parent: NotificationChannel,
        conversationChannelId: String,
        conversationShortcutId: String,
        displayName: CharSequence,
    ): NotificationChannel =
        NotificationChannel(conversationChannelId, displayName, parent.importance).apply {
            setConversationId(parent.id, conversationShortcutId)
            group = parent.group
            description = parent.description
            setShowBadge(parent.canShowBadge())
            setSound(parent.sound, parent.audioAttributes)
            enableLights(parent.shouldShowLights())
            lightColor = parent.lightColor
            enableVibration(parent.shouldVibrate())
            vibrationPattern = parent.vibrationPattern
            // No setBypassDnd: the platform ignores it unless the app holds
            // Do Not Disturb policy access, which this app never requests.
            lockscreenVisibility = parent.lockscreenVisibility
        }
}
