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
 * are frozen — never change the string literals. See #288.
 *
 * Routing is kept as pure functions (no Android types) so the kind/payload ->
 * channel mapping is unit-testable without an instrumented device.
 *
 * Note on the System / service channel: the persistent foreground-service
 * notification keeps its own long-lived channel
 * (`darkmatter.background_connection.v1`) defined in
 * [NotificationStreamForegroundService]; it is intentionally NOT redefined here
 * so a live FGS channel is never disturbed.
 *
 * Note on a Mentions channel: the issue asks for one, but the notification FFI
 * payload exposes no mention signal (no p-tag list, no `isMention` flag, and the
 * trigger enum is only NEW_MESSAGE / GROUP_INVITE). Detecting "the local user
 * was mentioned" is protocol/Rust-side classification, so a Mentions channel is
 * deferred until the FFI surfaces it. [forUpdate] funnels everything we can't
 * yet distinguish into the message channels.
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
     * kind:7 reactions to the local user's messages (surfaced by PR #190).
     * High importance so a reaction heads-up like a message; still its own
     * channel so users can mute it independently of mentions / direct messages.
     * The id is bumped from the original low-importance `reactions` channel
     * because Android can't raise a live channel's importance — the old one is
     * retired in [NotificationChannels.ensureChannels].
     */
    REACTIONS("reactions_v2", ChannelImportance.HIGH),

    /** kind:444 Welcomes and group-join events. */
    INVITES("invites", ChannelImportance.DEFAULT),
    ;

    companion object {
        /**
         * The legacy single channel that carried every alert before #288 split
         * them apart. Retired in favour of [DIRECT_MESSAGES]; deleted on the OS
         * side by [NotificationChannels.ensureChannels] so it doesn't linger as a
         * dead entry in the app's notification settings.
         */
        const val LEGACY_MESSAGES_CHANNEL_ID = "darkmatter.messages.v2"

        /**
         * The original low-importance reactions channel. Retired in favour of
         * the high-importance `reactions_v2` so reactions heads-up; deleted on
         * the OS side by [NotificationChannels.ensureChannels] (a live channel's
         * importance can't be raised, so it must be re-keyed).
         */
        const val LEGACY_REACTIONS_CHANNEL_ID = "reactions"

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
                        !update.reactionEmoji.isNullOrBlank() -> REACTIONS
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
