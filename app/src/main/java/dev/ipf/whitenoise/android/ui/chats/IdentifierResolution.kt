package dev.ipf.whitenoise.android.ui.chats

import androidx.annotation.StringRes

/**
 * Resolution state for a chat-list search query that parsed as a Nostr
 * identifier (npub / NIP-05) — issue #344. An npub resolves synchronously to
 * its key; a NIP-05 address has to be looked up over the network, so it passes
 * through [Resolving] before landing on [Resolved] or [Failed]. [None] is the
 * ordinary plain-text-query case where the chat list filters as before.
 */
internal sealed interface IdentifierResolution {
    data object None : IdentifierResolution

    data object Resolving : IdentifierResolution

    /** Resolved to a profile; [npub] is ready to hand to the profile sheet. */
    data class Resolved(
        val npub: String,
    ) : IdentifierResolution

    /** Resolution failed; [messageRes] is the inline error to show. */
    data class Failed(
        @StringRes val messageRes: Int,
        val arg: String? = null,
    ) : IdentifierResolution
}
