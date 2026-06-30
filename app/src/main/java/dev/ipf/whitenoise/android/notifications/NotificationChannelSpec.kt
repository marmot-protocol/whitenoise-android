package dev.ipf.whitenoise.android.notifications

import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

/**
 * Stable Android notification channel identities and the routing that maps a
 * [NotificationUpdateFfi] to the channel it belongs on.
 *
 * Channel IDs are a permanent contract with the OS: once created, Android won't
 * let an app rename or re-key a channel (only delete + recreate, which discards
 * the user's per-channel sound / vibration / importance overrides). So these IDs
 * are frozen — never change the string literals.
 *
 * Routing is kept as pure functions (no Android types) so the kind/payload ->
 * channel mapping is unit-testable without an instrumented device.
 *
 * The persistent foreground-service notification keeps its own long-lived
 * channel defined in [NotificationStreamForegroundService]; it is intentionally
 * NOT redefined here.
 */
enum class NotificationChannelSpec(
    val id: String,
    val importance: ChannelImportance,
) {
    /** 1:1 conversation messages. */
    DIRECT_MESSAGES("messages_dm", ChannelImportance.HIGH),

    /** Group conversation messages. */
    GROUP_MESSAGES("messages_group", ChannelImportance.HIGH),

    /**
     * Messages that mention the local user. High importance and routed ahead of
     * the DM/group split so the highest-signal unread can be muted and surfaced
     * independently of ordinary conversation traffic.
     */
    MENTIONS("mentions", ChannelImportance.HIGH),

    /**
     * Reactions to the local user's messages, on their own channel so they can
     * be muted independently. High importance so a reaction heads-up like a
     * message.
     */
    REACTIONS("reactions_v2", ChannelImportance.HIGH),

    /** Welcomes and group-join events. High importance so an invite heads-up. */
    INVITES("invites_v2", ChannelImportance.HIGH),

    /** Peer-driven membership changes such as being removed from a group. */
    MEMBERSHIP_CHANGES("membership_changes_v1", ChannelImportance.HIGH),
    ;

    companion object {
        /**
         * Map a notification to its channel using only the signals the FFI
         * payload actually carries:
         *  - GROUP_INVITE                       -> invites
         *  - NEW_MESSAGE with a reaction emoji  -> reactions
         *  - NEW_MESSAGE, DM                    -> direct messages
         *  - NEW_MESSAGE, group                 -> group messages
         */
        fun forUpdate(update: NotificationUpdateFfi): NotificationChannelSpec =
            when (update.trigger) {
                NotificationTriggerFfi.GROUP_INVITE -> INVITES
                NotificationTriggerFfi.NEW_MESSAGE ->
                    when {
                        // Route off the same sanitized-emoji predicate the
                        // formatter uses for tag/id/body, so the two sites can't
                        // disagree and post a reaction on the REACTIONS channel
                        // while it reuses the message card's identity.
                        LocalNotificationFormatter.isReaction(update) -> REACTIONS
                        // Mentions take precedence over the DM/group split: a
                        // mention is the highest-signal message, on its own channel.
                        update.isMention -> MENTIONS
                        update.isDm -> DIRECT_MESSAGES
                        else -> GROUP_MESSAGES
                    }
            }
    }
}

/**
 * Android importance levels, mirrored as a framework-free enum so routing stays
 * unit-testable. Mapped to `NotificationManager.IMPORTANCE_*` at channel
 * creation time.
 */
enum class ChannelImportance {
    HIGH,
    DEFAULT,
    LOW,
}
