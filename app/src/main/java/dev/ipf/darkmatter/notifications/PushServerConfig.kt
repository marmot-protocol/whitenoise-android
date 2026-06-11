package dev.ipf.darkmatter.notifications

import dev.ipf.darkmatter.BuildConfig

/**
 * Build-time configuration for the MIP-05 push gateway. The pubkey identifies
 * the push server that wraps the FCM payload as a gift-wrapped Nostr event;
 * the relay hint tells the server which relay to publish that event to so
 * Marmot can pick it up on the receiver side.
 *
 * Both values are sourced from `local.properties` (or the build environment)
 * via [BuildConfig.DARKMATTER_PUSH_SERVER_PUBKEY_HEX] and
 * [BuildConfig.DARKMATTER_PUSH_RELAY_HINT]. When the pubkey is unset the
 * config is [unconfigured] and the runtime skips push registration entirely
 * — the app still works, but only receives notifications while the
 * foreground-service WebSocket is alive.
 */
data class PushServerConfig(
    val serverPubkeyHex: String,
    val relayHint: String?,
) {
    companion object {
        /**
         * The current build's push config, or `null` if push is unconfigured
         * (no `DARKMATTER_PUSH_SERVER_PUBKEY_HEX` set). A blank or
         * whitespace-only pubkey counts as unset so an empty
         * `local.properties` line doesn't accidentally enable registration
         * against a malformed server identity.
         */
        fun current(): PushServerConfig? = fromRaw(BuildConfig.DARKMATTER_PUSH_SERVER_PUBKEY_HEX, BuildConfig.DARKMATTER_PUSH_RELAY_HINT)

        internal fun fromRaw(
            rawPubkey: String?,
            rawRelayHint: String?,
        ): PushServerConfig? {
            val pubkey = rawPubkey?.trim().orEmpty()
            if (pubkey.isEmpty()) return null
            val relayHint = rawRelayHint?.trim()?.takeIf { it.isNotEmpty() }
            return PushServerConfig(serverPubkeyHex = pubkey, relayHint = relayHint)
        }
    }
}
