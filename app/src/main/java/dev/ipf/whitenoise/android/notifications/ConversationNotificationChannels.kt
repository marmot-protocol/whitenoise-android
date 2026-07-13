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
 * messages on its DM/group channel and mentions on the mentions channel — so a
 * conversation channel is created per relevant parent. The channel id is derived
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
     * The parent whose conversation channel the "Customize sound & vibration"
     * deep link targets. A conversation spans its message parent plus mentions,
     * but the message parent is where the bulk of a chat's traffic lands, so the
     * sound the user sets there is what "this chat's sound" means to them. The
     * mentions conversation channel still exists and stays customizable from the
     * OS conversation list; we just don't point the single in-app row at it.
     */
    fun primaryMessageParent(isDm: Boolean): NotificationChannelSpec =
        if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES

    /**
     * Parents a conversation can receive per-conversation messages on: its
     * primary message parent and the mentions channel. Reactions and invites are
     * not conversation notifications (plain style, no shortcut), so they stay on
     * their parent channel and are intentionally excluded here.
     */
    fun relevantParents(isDm: Boolean): List<NotificationChannelSpec> = listOf(primaryMessageParent(isDm), NotificationChannelSpec.MENTIONS)

    /** Creates the conversation channel for every parent this conversation can receive on. */
    fun ensureConversationChannels(
        context: Context,
        conversationShortcutId: String,
        isDm: Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        relevantParents(isDm).forEach { parent ->
            ensureConversationChannel(manager, parent.id, conversationShortcutId)
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
    ): String? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return ensureConversationChannel(manager, parentChannelId, conversationShortcutId)
    }

    private fun ensureConversationChannel(
        manager: NotificationManager,
        parentChannelId: String,
        conversationShortcutId: String,
    ): String? {
        val conversationChannelId = conversationChannelId(parentChannelId, conversationShortcutId)
        // Already published: Android freezes channel settings after creation, so
        // recreating would wipe the user's per-conversation sound/vibration
        // overrides. Leave it alone.
        if (manager.getNotificationChannel(conversationChannelId) != null) return conversationChannelId
        val parent = manager.getNotificationChannel(parentChannelId) ?: return null
        manager.createNotificationChannel(conversationChannel(parent, conversationChannelId, conversationShortcutId))
        return conversationChannelId
    }

    // Clone the parent's importance and alerting defaults onto the conversation
    // channel at creation time; the user can then diverge per conversation from
    // the OS settings without affecting the parent or its other conversations.
    private fun conversationChannel(
        parent: NotificationChannel,
        conversationChannelId: String,
        conversationShortcutId: String,
    ): NotificationChannel =
        NotificationChannel(conversationChannelId, parent.name, parent.importance).apply {
            setConversationId(parent.id, conversationShortcutId)
            group = parent.group
            description = parent.description
            setShowBadge(parent.canShowBadge())
            setSound(parent.sound, parent.audioAttributes)
            enableLights(parent.shouldShowLights())
            lightColor = parent.lightColor
            enableVibration(parent.shouldVibrate())
            vibrationPattern = parent.vibrationPattern
            setBypassDnd(parent.canBypassDnd())
            lockscreenVisibility = parent.lockscreenVisibility
        }
}
