package dev.ipf.whitenoise.android.core

/**
 * Pure input handling for the identity-entry form (onboarding sign-in and the
 * add-account sheet). Mirrors [ProfileLink]'s shape check: a bech32 key is
 * `nsec1`/`npub1` plus 58 chars of the bech32 alphabet. Nothing here
 * bech32-decodes — the engine's login FFI stays the authority — this only
 * separates "shaped like a key" from "certainly not a key" so the inline
 * import error can say the right thing, and so scanned/pasted payloads are
 * normalized before they land in a password-masked field.
 */
object IdentityEntryInput {
    enum class Kind { SecretKey, EncryptedSecretKey, PublicKey, Invalid }

    private const val BECH32_KEY_LENGTH = 63
    private const val NOSTR_URI_PREFIX = "nostr:"
    private const val NCRYPTSEC_PREFIX = "ncryptsec1"

    // bech32 alphabet: lowercase a-z + 0-9 minus 'b' 'i' 'o' '1' (same class
    // ProfileLink uses for npub bodies).
    private val BECH32_BODY = Regex("^[ac-hj-np-z02-9]{58}$")

    // NIP-49 encrypted keys are variable-length (version byte, KDF params,
    // ciphertext), so unlike nsec/npub there is no fixed 63-char shape —
    // bound loosely and let the engine be the authority on submit.
    private val NCRYPTSEC_BODY = Regex("^[ac-hj-np-z02-9]{50,300}$")

    fun classify(raw: String): Kind {
        val trimmed = raw.trim()
        return when {
            isBech32Key(trimmed, "nsec1") -> Kind.SecretKey
            // An encrypted secret is still secret-shaped for masking and
            // field entry, but distinct from a raw nsec: the engine's login
            // and the direct-import gate accept plaintext keys only.
            isNcryptsecKey(trimmed) -> Kind.EncryptedSecretKey
            isBech32Key(trimmed, "npub1") -> Kind.PublicKey
            else -> Kind.Invalid
        }
    }

    /**
     * Normalizes a scanned QR payload into field text: a bare nsec/npub, a
     * `nostr:`-prefixed one, or any profile-link form [ProfileLink] parses.
     * Returns null when the payload is not a key so callers surface an error
     * instead of silently filling the field with garbage the password mask
     * would hide.
     */
    fun scannedValue(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        ProfileLink.parse(trimmed)?.let { return it.npub }
        val candidate =
            if (trimmed.startsWith(NOSTR_URI_PREFIX, ignoreCase = true)) {
                trimmed.drop(NOSTR_URI_PREFIX.length)
            } else {
                trimmed
            }
        return candidate.takeIf { classify(it) != Kind.Invalid }
    }

    /**
     * Clipboard paste for the identity field. Unlike
     * [ClipboardPasteAffordance.pasteValue] (public identifiers only, so it
     * rejects secrets by design) this accepts nsec too. A recognized key is
     * normalized via [scannedValue]; anything else pastes trimmed as-is —
     * the user asked for the paste, and the engine rejects non-keys with the
     * inline error on submit.
     */
    fun pasteValue(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return scannedValue(trimmed) ?: trimmed
    }

    private fun isBech32Key(
        value: String,
        prefix: String,
    ): Boolean =
        value.length == BECH32_KEY_LENGTH &&
            value.startsWith(prefix) &&
            BECH32_BODY.matches(value.substring(prefix.length))

    private fun isNcryptsecKey(value: String): Boolean =
        value.startsWith(NCRYPTSEC_PREFIX) &&
            NCRYPTSEC_BODY.matches(value.substring(NCRYPTSEC_PREFIX.length))
}
