package dev.ipf.darkmatter.notifications

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
 * channel (`darkmatter.background_connection.v1`) defined in
 * [NotificationStreamForegroundService]; it is intentionally NOT redefined here.
 */
enum class NotificationChannelSpec(
    val id: String,
    val importance: ChannelImportance,
) {
    /**
     * 1:1 conversation messages. Inherits the role of the legacy single channel
     * so users keep the sound / vibration they set on it — see
     * [LEGACY_MESSAGES_CHANNEL_ID] for how the old ID is retired.
     */
    DIRECT_MESSAGES("messages_dm", ChannelImportance.HIGH),

    /** Group conversation messages. */
    GROUP_MESSAGES("messages_group", ChannelImportance.HIGH),

    /**
     * Reactions to the local user's messages, on their own channel so they can
     * be muted independently. High importance so a reaction heads-up like a
     * message; re-keyed from the original low-importance `reactions` because
     * Android can't raise a live channel's importance — the old id is retired in
     * [NotificationChannels.ensureChannels].
     */
    REACTIONS("reactions_v2", ChannelImportance.HIGH),

    /**
     * Welcomes and group-join events. High importance so an invite heads-up;
     * re-keyed from the original default-importance `invites` for the same
     * reason as [REACTIONS], with the old id retired in
     * [NotificationChannels.ensureChannels].
     */
    INVITES("invites_v2", ChannelImportance.HIGH),

    /** Zapstore app-update availability notices. Low importance: visible, no buzz. */
    APP_UPDATES("app_updates_v1", ChannelImportance.LOW),
    ;

    companion object {
        /**
         * The legacy single channel that carried every alert before the per-type
         * split. Retired in favour of [DIRECT_MESSAGES]; deleted on the OS side
         * by [NotificationChannels.ensureChannels] so it doesn't linger as a dead
         * entry in the app's notification settings.
         */
        const val LEGACY_MESSAGES_CHANNEL_ID = "darkmatter.messages.v2"

        /** The original low-importance reactions channel, re-keyed to [REACTIONS]. */
        const val LEGACY_REACTIONS_CHANNEL_ID = "reactions"

        /** The original default-importance invites channel, re-keyed to [INVITES]. */
        const val LEGACY_INVITES_CHANNEL_ID = "invites"

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
