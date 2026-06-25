package dev.ipf.darkmatter.core

/**
 * Classifies a chat-list search query as a Nostr identifier the user pasted
 * (an `npub` / `nostr:` / `whitenoise://profile/...` link, or a NIP-05
 * `name@domain` address) versus a plain-text filter string.
 *
 * Plain-text queries keep behaving as before (filter the chat list by title /
 * preview); identifier queries drive the resolve-to-profile affordance in the
 * chat-list search results (#344). Keeping the classification here makes it
 * unit-testable without standing up the Compose UI.
 */
object ChatListIdentifierSearch {
    /** A recognised identifier in a chat-list search query. */
    sealed interface Identifier {
        /**
         * An npub / nostr: / White Noise profile link that already carries the
         * key — no network needed, the [npub] is ready to hand to the profile
         * sheet.
         */
        data class Npub(
            val npub: String,
        ) : Identifier

        /**
         * A NIP-05 internet identifier (`<local>@<domain>`) that must be
         * resolved to a pubkey over the network before the profile sheet can
         * open. [identifier] is the trimmed, original-cased address for display.
         */
        data class Nip05(
            val identifier: String,
        ) : Identifier
    }

    /**
     * Recognise [rawQuery] as an [Identifier], or null when it's a plain-text
     * search. npub is checked first: an `npub1…` value is unambiguous and never
     * a NIP-05. NIP-05 only matches the strict `<local>@<domain-with-a-dot>`
     * shape, so ordinary words and partial inputs stay plain-text queries.
     */
    fun classify(rawQuery: String): Identifier? {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return null
        ProfileLink.parse(trimmed)?.let { return Identifier.Npub(it.npub) }
        if (ProfileFieldValidation.isAcceptableNip05(trimmed) && trimmed.contains('@')) {
            return Identifier.Nip05(trimmed)
        }
        return null
    }
}
