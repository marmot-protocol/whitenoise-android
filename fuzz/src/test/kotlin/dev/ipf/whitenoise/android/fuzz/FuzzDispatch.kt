package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider

enum class IdentitySubtarget {
    ProfileLink,
    RecipientNormalize,
    RecipientTokenize,
    PlausibleClipboard,
    ;

    companion object {
        val COUNT: Int = entries.size

        fun fromId(id: Int): IdentitySubtarget = entries[id % entries.size]
    }
}

enum class ZapstoreSubtarget {
    NostrEventJson,
    RelayEnvelopeFrames,
    RelayEnvelopeSequence,
    ;

    companion object {
        val COUNT: Int = entries.size

        fun fromId(id: Int): ZapstoreSubtarget = entries[id % entries.size]
    }
}

enum class Nip55Subtarget {
    ParseContentRow,
    ParseActivityResult,
    SignedEventPubkeyHelpers,
    ;

    companion object {
        val COUNT: Int = entries.size

        fun fromId(id: Int): Nip55Subtarget = entries[id % entries.size]
    }
}

enum class GroupSystemSubtarget {
    Json,
    ;

    companion object {
        val COUNT: Int = entries.size
    }
}

/** A single selector keeps image-container reproducers compatible with shared triage tooling. */
enum class ImageContainerSubtarget {
    AllContainers,
    ;

    companion object {
        val COUNT: Int = entries.size
    }
}

/**
 * Consumes one selector byte mapped onto `0..count-1`.
 *
 * Jazzer reads [FuzzedDataProvider.consumeByte] from the end of the remaining input, so
 * checked-in seed corpora store the selector as the final byte.
 */
fun FuzzedDataProvider.consumeSubtarget(count: Int): Int {
    val id = consumeByte().toInt() and 0xFF
    return id % count
}
