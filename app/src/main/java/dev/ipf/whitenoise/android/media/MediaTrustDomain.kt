package dev.ipf.whitenoise.android.media

/**
 * The data-access domain of one untrusted still-image input (#3373). An isolated worker generation serves exactly
 * one domain for its whole life, so a decoder compromised by one input reaches only that domain's other inputs.
 *
 * The rule every domain keeps: each input's bytes are fixed by a principal who can already read the whole domain,
 * and whatever the worker returns stays where that principal can already see it. Bytes a URL's host chooses, with
 * no digest a member pinned, never enter a [Conversation]; anything a caller cannot place under the rule is
 * [Unique]. Keys hold account and conversation identifiers, so their text form is redacted.
 */
sealed interface MediaTrustDomain {
    /**
     * One conversation of one account, for bytes a member fixed: its encrypted message media, thumbnails and
     * viewer, custom emoji received as its message attachments, link-preview JPEGs whose digest the message pins,
     * and the group's encrypted, hash-pinned image wherever it is drawn. A public URL's picture, a GIPHY frame or a
     * Nostr event's image is never one of these: its host chooses the bytes.
     */
    data class Conversation(
        val account: String,
        val conversation: String,
    ) : MediaTrustDomain {
        /** Redacted: the kind only. */
        override fun toString(): String = "Conversation"
    }

    /**
     * Kind-0 profile pictures and banners seen by one account. They are published to relays, so anyone can already
     * fetch all of them and anyone may choose their bytes; a group's image is never one of these.
     */
    data class PublicProfiles(
        val account: String,
    ) : MediaTrustDomain {
        /** Redacted: the kind only. */
        override fun toString(): String = "PublicProfiles"
    }

    /**
     * Images the account's user picked from the device to send into one conversation, while unedited: composer
     * picks and previews, the editor's source inspection, staged drafts, thumbhashes and just-sent thumbnails. The
     * worker's output is sent there, so it may see only that conversation's picks, never another conversation's.
     * An edited render and the editor's own preview decode [Unique] instead, so a worker an earlier pick compromised
     * cannot restore what a crop, blur or mark removed.
     */
    data class Outgoing(
        val account: String,
        val conversation: String,
    ) : MediaTrustDomain {
        /** Redacted: the kind only. */
        override fun toString(): String = "Outgoing"
    }

    /**
     * Display-only images from this device for one account: the user's own custom-emoji files and stored private
     * contact pictures. Their decoded pixels are drawn on this device, or exported only to this user's own
     * notification surfaces: notification emoji are re-encoded as PNG and shared with the system through the app's
     * FileProvider. They are never sent; an emoji is sent as its stored file.
     */
    data class Local(
        val account: String,
    ) : MediaTrustDomain {
        /** Redacted: the kind only. */
        override fun toString(): String = "Local"
    }

    /** No declared domain: the input gets a fresh worker generation of its own, the fail-safe default. */
    data object Unique : MediaTrustDomain

    companion object {
        /** [Conversation] for [account] and [conversation], or [Unique] when either is unknown. */
        fun conversation(
            account: String?,
            conversation: String?,
        ): MediaTrustDomain =
            if (account.isNullOrEmpty() || conversation.isNullOrEmpty()) {
                Unique
            } else {
                Conversation(account, conversation)
            }

        /** [PublicProfiles] for [account], or [Unique] when no account is known. */
        fun publicProfiles(account: String?): MediaTrustDomain =
            if (account.isNullOrEmpty()) {
                Unique
            } else {
                PublicProfiles(account)
            }

        /** [Outgoing] for [account]'s picks into [conversation], or [Unique] when either is unknown. */
        fun outgoing(
            account: String?,
            conversation: String?,
        ): MediaTrustDomain =
            if (account.isNullOrEmpty() || conversation.isNullOrEmpty()) {
                Unique
            } else {
                Outgoing(account, conversation)
            }

        /** [Local] for [account], or [Unique] when no account is known, as before sign-up completes. */
        fun local(account: String?): MediaTrustDomain = if (account.isNullOrEmpty()) Unique else Local(account)
    }
}
