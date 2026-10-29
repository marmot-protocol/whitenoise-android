package dev.ipf.whitenoise.android.media

/**
 * The data-access domain of one untrusted still-image input (#3373): whoever can see one input of a domain
 * can already see all of them. An isolated worker generation serves exactly one domain for its whole life,
 * so a decoder compromised by one input reaches only content its domain's principal could already read.
 * Keys hold account and conversation identifiers, so their text form is redacted.
 */
sealed interface MediaTrustDomain {
    /**
     * One conversation of one account: its message media, thumbnails and viewers, its notification artwork
     * and custom emoji, link-preview and event-card images shown inside it, and the group's own image.
     */
    data class Conversation(
        val account: String,
        val conversation: String,
    ) : MediaTrustDomain {
        /** Redacted: the kind only. */
        override fun toString(): String = "Conversation"
    }

    /**
     * Kind-0 profile pictures seen by one account. They are published to relays, so anyone can already
     * fetch all of them; a group's image is never one of these, it belongs to its conversation.
     */
    data class PublicProfiles(
        val account: String,
    ) : MediaTrustDomain {
        /** Redacted: the kind only. */
        override fun toString(): String = "PublicProfiles"
    }

    /** Images the account's user picked from this device, before they are sent or published. */
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

        /** [Local] for [account], or [Unique] when no account is known, as before sign-up completes. */
        fun local(account: String?): MediaTrustDomain = if (account.isNullOrEmpty()) Unique else Local(account)
    }
}
