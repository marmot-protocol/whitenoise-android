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
     * One file the account's user picked from the device to send into one conversation, for its whole life in the
     * draft: its composer and shelf previews, staging (inspection, EXIF and the Original or standard render), the
     * photo editor's inspection, preview and edited render, its thumbhash and its just-sent thumbnail. Gallery files
     * come from many authors, so each pick gets its own worker: a hostile file can only ever see itself, never
     * another pick or that pick's unredacted original. Its own worker seeing its own original reveals nothing new.
     * [pick] is the composer slot's id, which a restored draft attachment keeps as its own, or a document's URI.
     */
    data class Outgoing(
        val account: String,
        val conversation: String,
        val pick: String,
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

        /** [Outgoing] for one [pick] of [account] into [conversation], or [Unique] when any of them is unknown. */
        fun outgoing(
            account: String?,
            conversation: String?,
            pick: String?,
        ): MediaTrustDomain =
            if (account.isNullOrEmpty() || conversation.isNullOrEmpty() || pick.isNullOrEmpty()) {
                Unique
            } else {
                Outgoing(account, conversation, pick)
            }

        /** [Local] for [account], or [Unique] when no account is known, as before sign-up completes. */
        fun local(account: String?): MediaTrustDomain = if (account.isNullOrEmpty()) Unique else Local(account)
    }
}
