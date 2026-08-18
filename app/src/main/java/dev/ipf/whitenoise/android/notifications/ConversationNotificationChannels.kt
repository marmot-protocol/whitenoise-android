package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect

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
 * Ordinary messages always use a conversation child so Android's People and
 * conversation controls keep working. Mentions, reactions, invites, and agent
 * activity inherit their stable global channels until the user explicitly asks
 * for a custom child. The channel id remains deterministic so legacy children,
 * the post path, and settings deep links all resolve the same user-owned channel.
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
        vibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ): String {
        val base = "$parentChannelId:$CONVERSATION_CHANNEL_INFIX:$conversationShortcutId"
        return if (vibrationPattern == ConversationVibrationPattern.SYSTEM_DEFAULT) {
            base
        } else {
            "$base:vibration:${vibrationPattern.channelToken}"
        }
    }

    /**
     * The default parent for callers that want the conversation's ordinary
     * message settings. Typed settings rows pass their parent explicitly.
     */
    fun primaryMessageParent(isDm: Boolean): NotificationChannelSpec =
        if (isDm) NotificationChannelSpec.DIRECT_MESSAGES else NotificationChannelSpec.GROUP_MESSAGES

    /** Reads the active channel back from Android, including OS-side edits. */
    fun effectiveVibration(
        context: Context,
        conversationShortcutId: String,
        isDm: Boolean,
        selectedPattern: ConversationVibrationPattern,
    ): EffectiveConversationVibration {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId =
            conversationChannelId(
                primaryMessageParent(isDm).id,
                conversationShortcutId,
                selectedPattern,
            )
        val channel = manager?.getNotificationChannel(channelId)
        return when {
            channel == null ->
                EffectiveConversationVibration(selectedPattern, enabled = true, overriddenByAndroid = false)
            !channel.shouldVibrate() ->
                EffectiveConversationVibration(pattern = null, enabled = false, overriddenByAndroid = true)
            selectedPattern == ConversationVibrationPattern.SYSTEM_DEFAULT &&
                channel.matchesParentVibration(manager, primaryMessageParent(isDm).id) ->
                EffectiveConversationVibration(selectedPattern, enabled = true, overriddenByAndroid = false)
            else -> {
                val effectivePattern = channel.recognizedVibrationPattern()
                EffectiveConversationVibration(
                    pattern = effectivePattern,
                    enabled = true,
                    overriddenByAndroid = effectivePattern != selectedPattern,
                )
            }
        }
    }

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

    /**
     * Creates only the required ordinary-message child. Optional event children
     * are provisioned lazily by [ConversationNotificationRouting].
     */
    fun ensureConversationChannels(
        context: Context,
        conversationShortcutId: String,
        isDm: Boolean,
        conversationTitle: String? = null,
        primaryVibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
        sourceVibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ) {
        val primaryParent = primaryMessageParent(isDm)
        ensureConversationChannel(
            context = context,
            parentChannelId = primaryParent.id,
            conversationShortcutId = conversationShortcutId,
            conversationTitle = conversationTitle,
            vibrationPattern = primaryVibrationPattern,
            sourceVibrationPattern = sourceVibrationPattern,
        )
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
        vibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
        sourceVibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ): String? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val parentSpec = NotificationChannelSpec.entries.firstOrNull { it.id == parentChannelId }
        val baseName = parentSpec?.let { NotificationChannels.baseName(context, it) }
        val usesCustomScope = parentSpec?.conversationPolicy == ConversationChannelPolicy.GLOBAL_UNTIL_OVERRIDE
        val customDisplayName =
            if (usesCustomScope && !conversationTitle.isNullOrBlank() && baseName != null) {
                context.getString(
                    dev.ipf.whitenoise.android.R.string.notification_channel_custom_name,
                    conversationTitle.trim(),
                    baseName,
                )
            } else {
                null
            }
        val customDescription =
            if (usesCustomScope && !conversationTitle.isNullOrBlank() && baseName != null) {
                context.getString(
                    dev.ipf.whitenoise.android.R.string.notification_channel_custom_description,
                    baseName,
                    conversationTitle.trim(),
                )
            } else {
                null
            }
        return ensureConversationChannel(
            manager = manager,
            parentChannelId = parentChannelId,
            conversationShortcutId = conversationShortcutId,
            conversationTitle = conversationTitle,
            vibrationPattern = vibrationPattern,
            sourceVibrationPattern = sourceVibrationPattern,
            baseName = baseName,
            customDisplayName = customDisplayName,
            customDescription = customDescription,
        )
    }

    private fun ensureConversationChannel(
        manager: NotificationManager,
        parentChannelId: String,
        conversationShortcutId: String,
        conversationTitle: String?,
        vibrationPattern: ConversationVibrationPattern,
        sourceVibrationPattern: ConversationVibrationPattern,
        baseName: String?,
        customDisplayName: String?,
        customDescription: String?,
    ): String? {
        val conversationChannelId = conversationChannelId(parentChannelId, conversationShortcutId, vibrationPattern)
        val parent = manager.getNotificationChannel(parentChannelId) ?: return null
        val displayName =
            customDisplayName ?: conversationChannelDisplayName(baseName ?: parent.name, conversationTitle)
        val displayDescription = customDescription ?: parent.description
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
            if (displayDescription != null && existing.description != displayDescription) {
                existing.description = displayDescription
                republish = true
            }
            if (shouldDowngradeImportance(existing, parent)) {
                existing.importance = parent.importance
                republish = true
            }
            if (republish) manager.createNotificationChannel(existing)
            return conversationChannelId
        }
        // A channel's alert behavior is immutable after creation. Custom
        // choices therefore get one of three bounded, deterministic channel
        // versions. Clone the previously active child when available so sound,
        // importance, lights, badge, and other effective OS choices carry over;
        // never delete the old child, because that would erase its user-owned
        // settings and any notification still routed through it.
        val source =
            manager.getNotificationChannel(
                conversationChannelId(parentChannelId, conversationShortcutId, sourceVibrationPattern),
            ) ?: manager.getNotificationChannel(
                conversationChannelId(parentChannelId, conversationShortcutId),
            ) ?: parent
        manager.createNotificationChannel(
            conversationChannel(
                source = source,
                parent = parent,
                parentChannelId = parentChannelId,
                conversationChannelId = conversationChannelId,
                conversationShortcutId = conversationShortcutId,
                displayName = displayName,
                displayDescription = displayDescription,
                vibrationPattern = vibrationPattern,
            ),
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
        source: NotificationChannel,
        parent: NotificationChannel,
        parentChannelId: String,
        conversationChannelId: String,
        conversationShortcutId: String,
        displayName: CharSequence,
        displayDescription: String?,
        vibrationPattern: ConversationVibrationPattern,
    ): NotificationChannel =
        NotificationChannel(conversationChannelId, displayName, source.importance).apply {
            setConversationId(parentChannelId, conversationShortcutId)
            description = displayDescription
            setShowBadge(source.canShowBadge())
            setSound(source.sound, source.audioAttributes)
            enableLights(source.shouldShowLights())
            lightColor = source.lightColor
            setAllowBubbles(source.canBubble())
            if (vibrationPattern == ConversationVibrationPattern.SYSTEM_DEFAULT) {
                enableVibration(parent.shouldVibrate())
                this.vibrationPattern = parent.vibrationPattern
            } else {
                applyConversationVibration(this, vibrationPattern)
            }
            // Preserve an effective DND bypass when the OS grants this app
            // policy access. Without that special access Android ignores the
            // request, but the old channel remains intact and is never deleted.
            if (source.canBypassDnd()) runCatching { setBypassDnd(true) }
            lockscreenVisibility = source.lockscreenVisibility
        }
}

private fun NotificationChannel.matchesParentVibration(
    manager: NotificationManager,
    parentChannelId: String,
): Boolean {
    val parent = manager.getNotificationChannel(parentChannelId) ?: return false
    return parent.shouldVibrate() == shouldVibrate() &&
        nullableWaveformsEqual(parent.vibrationPattern, vibrationPattern)
}

private fun NotificationChannel.recognizedVibrationPattern(): ConversationVibrationPattern? =
    vibrationPattern?.let { actual ->
        ConversationVibrationPattern.entries.firstOrNull { candidate ->
            candidate.waveform?.contentEquals(actual) == true
        }
    }

private fun nullableWaveformsEqual(
    first: LongArray?,
    second: LongArray?,
): Boolean =
    when {
        first == null -> second == null
        second == null -> false
        else -> first.contentEquals(second)
    }

data class EffectiveConversationVibration(
    val pattern: ConversationVibrationPattern?,
    val enabled: Boolean,
    val overriddenByAndroid: Boolean,
)

/**
 * Applies a custom waveform through the newest portable channel API.
 *
 * The injectable sdkInt makes both branches unit-testable; production uses
 * Build.VERSION.SDK_INT, so the API-35 call remains guarded at runtime.
 */
@SuppressLint("NewApi")
internal fun applyConversationVibration(
    channel: NotificationChannel,
    pattern: ConversationVibrationPattern,
    sdkInt: Int = Build.VERSION.SDK_INT,
    vibrationEffectFactory: (LongArray) -> VibrationEffect? = { waveform ->
        VibrationEffect.createWaveform(waveform, -1)
    },
) {
    val waveform = pattern.waveform ?: return
    channel.enableVibration(true)
    if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        // These patterns use only timings, not device-specific primitives. If
        // an OEM rejects effect construction, retain the API-26 waveform path.
        val effect = runCatching { vibrationEffectFactory(waveform.copyOf()) }.getOrNull()
        if (effect != null) {
            channel.setVibrationEffect(effect)
            return
        }
    }
    channel.vibrationPattern = waveform.copyOf()
}
