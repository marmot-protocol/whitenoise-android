package dev.ipf.whitenoise.android.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val HRP_NOTE = "note"
private const val HRP_NEVENT = "nevent"
private const val HRP_NADDR = "naddr"
private const val TLV_SPECIAL = 0
private const val TLV_RELAY = 1
private const val TLV_AUTHOR = 2
private const val TLV_KIND = 3
private const val HASH_BYTES = 32
private const val KIND_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val NIBBLE_BITS = 4
private const val NIBBLE_MASK = 0x0f
private val HEX_CHARS = "0123456789abcdef".toCharArray()

/**
 * NIP-19 Bech32/TLV decoder for public event pointers (`note`, `nevent`, `naddr`).
 *
 * Profile references (`npub`, `nprofile`) are decoded by MarmotKit's `accountIdHex` since 0.10.0 and
 * no longer here (#1584). Event-reference relay TLVs are retained as untrusted metadata and must be
 * validated again immediately before callers dial them.
 */
internal object NostrEventReferenceDecoder {
    /** Strictly decode a public event pointer; private-key and profile forms return null. */
    fun eventReference(reference: String): NostrEventReference? =
        decodeEventPayload(reference)?.let { (hrp, payload) ->
            when (hrp) {
                HRP_NOTE ->
                    payload
                        .takeIf { it.size == HASH_BYTES }
                        ?.toHexString()
                        ?.let(NostrEventReference::Event)
                HRP_NEVENT -> decodeEventPointer(payload)
                HRP_NADDR -> decodeAddressPointer(payload)
                else -> null
            }
        }
}

private fun decodeEventPayload(reference: String): Pair<String, List<Int>>? =
    NostrBech32Codec
        .decode(reference.trim())
        ?.let { decoded ->
            NostrBech32Codec
                .convertBits(decoded.data, fromBits = 5, toBits = BITS_PER_BYTE, pad = false)
                ?.let { payload -> decoded.hrp to payload }
        }

@Suppress("MaxLineLength")
private fun decodeEventPointer(payload: List<Int>): NostrEventReference.Event? = NostrTlvCodec.parse(payload)?.let(::decodeEventFields)

private fun decodeEventFields(fields: Map<Int, List<List<Int>>>): NostrEventReference.Event? {
    val id = NostrTlvCodec.unique(fields, TLV_SPECIAL, HASH_BYTES)?.toHexString()
    val authorValid = NostrTlvCodec.optionalFieldIsValid(fields, TLV_AUTHOR, HASH_BYTES)
    val kindValid = NostrTlvCodec.optionalFieldIsValid(fields, TLV_KIND, KIND_BYTES)
    val author = NostrTlvCodec.optionalUnique(fields, TLV_AUTHOR, HASH_BYTES)?.toHexString()
    val kindBytes = NostrTlvCodec.optionalUnique(fields, TLV_KIND, KIND_BYTES)
    val kind = kindBytes?.toUIntBigEndian()?.takeIf { it <= Int.MAX_VALUE.toUInt() }
    val validKind = kindBytes == null || kind != null
    val relayHints = fields.relayHints()
    return id
        ?.takeIf { authorValid && kindValid && validKind }
        ?.let { eventId ->
            NostrEventReference.Event(
                eventIdHex = eventId,
                authorPubkeyHex = author,
                kind = kind,
                relayHints = relayHints,
            )
        }
}

private fun decodeAddressPointer(payload: List<Int>): NostrEventReference.Address? =
    NostrTlvCodec.parse(payload)?.let { fields ->
        val identifier = NostrTlvCodec.unique(fields, TLV_SPECIAL)?.decodeUtf8Identifier()
        val author = NostrTlvCodec.unique(fields, TLV_AUTHOR, HASH_BYTES)?.toHexString()
        val kind =
            NostrTlvCodec
                .unique(fields, TLV_KIND, KIND_BYTES)
                ?.toUIntBigEndian()
                ?.takeIf { it <= Int.MAX_VALUE.toUInt() }
        if (identifier != null && author != null && kind != null) {
            NostrEventReference.Address(
                kind = kind,
                authorPubkeyHex = author,
                identifier = identifier,
                relayHints = fields.relayHints(),
            )
        } else {
            null
        }
    }

private fun NostrTlvFields.relayHints(): List<String> =
    this[TLV_RELAY]
        .orEmpty()
        .asSequence()
        .mapNotNull { it.decodeUtf8Text() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_RELAY_HINTS)
        .toList()

private fun List<Int>.decodeUtf8Identifier(): String? =
    decodeUtf8Text()?.takeUnless { identifier ->
        identifier.any { it == '\u0000' || it.isISOControl() }
    }

private fun List<Int>.decodeUtf8Text(): String? =
    runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(map(Int::toByte).toByteArray()))
            .toString()
    }.getOrNull()?.takeUnless { text -> text.any(Char::isISOControl) }

private fun List<Int>.toHexString(): String =
    buildString(size * 2) {
        for (byte in this@toHexString) {
            append(HEX_CHARS[(byte ushr NIBBLE_BITS) and NIBBLE_MASK])
            append(HEX_CHARS[byte and NIBBLE_MASK])
        }
    }

private fun List<Int>.toUIntBigEndian(): UInt? {
    if (size != KIND_BYTES) return null
    var result = 0u
    for (byte in this) result = (result shl BITS_PER_BYTE) or byte.toUInt()
    return result
}

internal sealed interface NostrEventReference {
    val stableId: String
    val relayHints: List<String>

    data class Event(
        val eventIdHex: String,
        val authorPubkeyHex: String? = null,
        val kind: UInt? = null,
        override val relayHints: List<String> = emptyList(),
    ) : NostrEventReference {
        override val stableId: String = "event:$eventIdHex"
    }

    data class Address(
        val kind: UInt,
        val authorPubkeyHex: String,
        val identifier: String,
        override val relayHints: List<String> = emptyList(),
    ) : NostrEventReference {
        override val stableId: String = "address:$kind:$authorPubkeyHex:$identifier"
    }
}

private const val MAX_RELAY_HINTS = 4
