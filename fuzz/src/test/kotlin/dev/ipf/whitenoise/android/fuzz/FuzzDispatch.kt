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

/** Consumes exactly one input byte mapped evenly onto 0..count-1. */
fun FuzzedDataProvider.consumeSubtarget(count: Int): Int {
    val id = consumeByte().toInt() and 0xFF
    return id % count
}
