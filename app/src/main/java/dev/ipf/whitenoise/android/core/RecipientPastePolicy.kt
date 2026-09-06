package dev.ipf.whitenoise.android.core

import java.util.Locale

/** The safe, UI-independent outcome of an explicit recipient-field paste. */
internal sealed interface RecipientPasteDecision {
    /** Replace the current selection with this already-normalized public identifier. */
    data class Accept(
        val value: String,
    ) : RecipientPasteDecision

    /** Let the platform insert ordinary non-identity text with its normal IME behavior. */
    data class PassThrough(
        val value: String,
    ) : RecipientPasteDecision

    /** Consume the paste without exposing its content to recipient search or resolution. */
    data class Reject(
        val reason: RecipientPasteRejection,
    ) : RecipientPasteDecision
}

internal enum class RecipientPasteRejection {
    InvalidOrAmbiguous,
    TooLarge,
}

/**
 * Validates explicit clipboard content before it crosses into recipient search.
 *
 * All clipboard items are evaluated as one transaction. Identity-bearing text either produces
 * one checksum-valid canonical npub or is rejected; surrounding clipboard prose is never
 * returned with an accepted identity.
 */
internal object RecipientPastePolicy {
    const val MAX_UTF8_BYTES = 16 * 1024
    const val MAX_ITEMS = 32

    private const val NPUB_LENGTH = 63
    private const val NPUB_PREFIX = "npub1"
    private val hexPublicKey = Regex("^[0-9a-fA-F]{64}$")
    private val embeddedHexPublicKey = Regex("(?i)(?<![\\p{L}\\p{N}_-])[0-9a-f]{64}(?![\\p{L}\\p{N}_-])")

    // Capture a whole Unicode token around one '@'; the canonical validator below decides
    // whether that token is an accepted NIP-05 shape. Keeping tokenization broader than the
    // validator prevents non-ASCII identities from evading cross-type ambiguity checks.
    private val possibleNip05Token = Regex("[^@\\s]+@[^@\\s]+")
    private val unsupportedNip19Prefixes =
        listOf(
            "nprofile1",
            "nsec1",
            "note1",
            "nevent1",
            "naddr1",
        )
    private val nip19Prefixes = listOf(NPUB_PREFIX) + unsupportedNip19Prefixes
    private val bech32DataCharacters = "qpzry9x8gf2tvdw0s3jn54khce6mua7l".toSet()

    /** Decide one atomic paste without logging, persisting, resolving, or otherwise exporting it. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount") // Identity conflicts fail closed at their detection point.
    fun evaluate(items: List<String>): RecipientPasteDecision {
        if (items.isEmpty() || items.size > MAX_ITEMS) return rejected()
        if (items.exceedsUtf8Limit()) {
            return RecipientPasteDecision.Reject(RecipientPasteRejection.TooLarge)
        }

        val pastedText = items.joinToString(separator = "\n")
        val scan = scanIdentityCandidates(pastedText)
        val nonBlankItems = items.map(String::trim).filter(String::isNotEmpty)
        val exactIdentities = nonBlankItems.mapNotNull(::strictlyNormalizedWholeField)
        val everyNonBlankItemIsExact = exactIdentities.size == nonBlankItems.size
        val distinctExactIdentities = exactIdentities.distinct()
        val repeatedExactNip05 =
            distinctExactIdentities
                .singleOrNull()
                ?.takeIf { everyNonBlankItemIsExact && it.kind == IdentityKind.Nip05 }

        // A valid NIP-05 local part may legitimately begin with `npub1` or another NIP-19
        // prefix. Prefer the whole-field interpretation unless the address actually embeds
        // a checksum-valid NIP-19 value or a raw public key.
        if (repeatedExactNip05 != null) {
            val containsEmbeddedIdentity =
                scan.distinctNpubs.isNotEmpty() ||
                    pastedText.containsChecksumValidNip19() ||
                    embeddedHexPublicKey.containsMatchIn(pastedText)
            return if (containsEmbeddedIdentity) {
                rejected()
            } else {
                RecipientPasteDecision.Accept(repeatedExactNip05.value)
            }
        }

        if (scan.malformed || scan.distinctNpubs.size > 1) return rejected()

        if (scan.distinctNpubs.size == 1) {
            val npub = scan.distinctNpubs.single()
            val conflictingExact = exactIdentities.any { it.kind != IdentityKind.Npub || it.value != npub }
            val containsAnotherIdentityType =
                embeddedHexPublicKey.containsMatchIn(pastedText) ||
                    possibleNip05Token
                        .findAll(pastedText)
                        .any { ProfileFieldValidation.isAcceptableNip05(it.value) }
            return if (conflictingExact || containsAnotherIdentityType) {
                rejected()
            } else {
                RecipientPasteDecision.Accept(npub)
            }
        }

        if (exactIdentities.isNotEmpty()) {
            return if (everyNonBlankItemIsExact && distinctExactIdentities.size == 1) {
                RecipientPasteDecision.Accept(distinctExactIdentities.single().value)
            } else {
                rejected()
            }
        }

        val containsOtherIdentity =
            scan.identityBearing ||
                embeddedHexPublicKey.containsMatchIn(pastedText) ||
                possibleNip05Token.findAll(pastedText).any { ProfileFieldValidation.isAcceptableNip05(it.value) }
        return if (containsOtherIdentity) rejected() else RecipientPasteDecision.PassThrough(pastedText)
    }

    private fun strictlyNormalizedWholeField(trimmed: String): ExactIdentity? =
        when {
            trimmed.isEmpty() -> null
            hexPublicKey.matches(trimmed) -> ExactIdentity(IdentityKind.Hex, trimmed.lowercase(Locale.ROOT))
            ProfileFieldValidation.isAcceptableNip05(trimmed) && trimmed.contains('@') ->
                ExactIdentity(IdentityKind.Nip05, trimmed)
            else ->
                ProfileLink
                    .parse(trimmed)
                    ?.npub
                    ?.takeIf(NostrProfileReference::isValidNpub)
                    ?.lowercase(Locale.ROOT)
                    ?.let { ExactIdentity(IdentityKind.Npub, it) }
        }

    private fun scanIdentityCandidates(raw: String): IdentityScan {
        val distinctNpubs = linkedSetOf<String>()
        var identityBearing = false
        var malformed = false
        var index = 0
        while (index < raw.length) {
            when {
                raw.regionMatches(index, NPUB_PREFIX, 0, NPUB_PREFIX.length, ignoreCase = true) -> {
                    identityBearing = true
                    val end = index + NPUB_LENGTH
                    val candidate = raw.substring(index, end.coerceAtMost(raw.length))
                    val hasBoundaries =
                        !raw.hasIdentifierCharacterBefore(index) &&
                            !raw.hasIdentifierCharacterAt(end)
                    if (
                        candidate.length == NPUB_LENGTH &&
                        hasBoundaries &&
                        NostrProfileReference.isValidNpub(candidate)
                    ) {
                        distinctNpubs += candidate.lowercase(Locale.ROOT)
                    } else {
                        malformed = true
                    }
                    index += NPUB_PREFIX.length
                }
                unsupportedNip19Prefixes.any { prefix ->
                    raw.regionMatches(index, prefix, 0, prefix.length, ignoreCase = true)
                } -> {
                    identityBearing = true
                    malformed = true
                    index += 1
                }
                else -> index += 1
            }
        }
        return IdentityScan(identityBearing, malformed, distinctNpubs)
    }

    private fun String.containsChecksumValidNip19(): Boolean {
        var index = 0
        while (index < length) {
            val prefix =
                nip19Prefixes.firstOrNull { candidate ->
                    regionMatches(index, candidate, 0, candidate.length, ignoreCase = true)
                }
            if (prefix == null) {
                index += 1
                continue
            }

            var end = index + prefix.length
            while (end < length && this[end].lowercaseChar() in bech32DataCharacters) end += 1
            val hasBoundaries =
                !hasIdentifierCharacterBefore(index) &&
                    !hasIdentifierCharacterAt(end)
            // Mixed-case NIP-19 is invalid as direct input, but normalizing it here detects
            // a recoverable identity before NIP-05 resolution lowercases and transmits it.
            val decoded =
                substring(index, end)
                    .takeIf { hasBoundaries }
                    ?.lowercase(Locale.ROOT)
                    ?.let(NostrBech32Codec::decode)
            if (decoded?.hrp == prefix.dropLast(1)) return true
            index += prefix.length
        }
        return false
    }

    @Suppress("ReturnCount") // Byte and item guards stop oversized clipboard input before allocation.
    private fun List<String>.exceedsUtf8Limit(): Boolean {
        var byteCount = 0
        forEachIndexed { index, item ->
            if (index > 0) byteCount += 1
            val remaining = MAX_UTF8_BYTES - byteCount
            if (remaining < 0 || item.length > remaining) return true
            byteCount += item.encodeToByteArray().size
            if (byteCount > MAX_UTF8_BYTES) return true
        }
        return false
    }

    private fun String.hasIdentifierCharacterBefore(index: Int): Boolean =
        index > 0 &&
            Character.codePointBefore(this, index).isIdentifierCharacter()

    private fun String.hasIdentifierCharacterAt(index: Int): Boolean =
        index < length &&
            Character.codePointAt(this, index).isIdentifierCharacter()

    private fun Int.isIdentifierCharacter(): Boolean =
        Character.isLetterOrDigit(this) ||
            this == '_'.code ||
            this == '-'.code

    private fun rejected() = RecipientPasteDecision.Reject(RecipientPasteRejection.InvalidOrAmbiguous)

    private data class IdentityScan(
        val identityBearing: Boolean,
        val malformed: Boolean,
        val distinctNpubs: Set<String>,
    )

    private data class ExactIdentity(
        val kind: IdentityKind,
        val value: String,
    )

    private enum class IdentityKind {
        Npub,
        Hex,
        Nip05,
    }
}
