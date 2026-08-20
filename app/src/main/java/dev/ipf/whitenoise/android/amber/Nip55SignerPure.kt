package dev.ipf.whitenoise.android.amber

import org.json.JSONObject

/** A NIP-55 operation and its wire encoding (intent `type` + ContentResolver authority suffix). */
enum class SignerOp(
    val intentType: String,
    val contentAuthoritySuffix: String,
) {
    GetPublicKey("get_public_key", ""),
    SignEvent("sign_event", "SIGN_EVENT"),
    Nip04Encrypt("nip04_encrypt", "NIP04_ENCRYPT"),
    Nip04Decrypt("nip04_decrypt", "NIP04_DECRYPT"),
    Nip44Encrypt("nip44_encrypt", "NIP44_ENCRYPT"),
    Nip44Decrypt("nip44_decrypt", "NIP44_DECRYPT"),
}

/** One permission requested during NIP-55 login. */
internal data class SignerPermission(
    val operation: SignerOp,
    val kind: Int? = null,
) {
    init {
        require(operation != SignerOp.GetPublicKey) { "get_public_key is not a reusable signer permission" }
        require((operation == SignerOp.SignEvent) == (kind != null)) {
            "only sign_event permissions carry a kind"
        }
        require(kind == null || kind >= 0) { "event kind must not be negative" }
    }
}

/** Interpretation of a NIP-55 ContentResolver row. */
sealed interface ContentRowOutcome {
    data class Value(
        val value: String,
    ) : ContentRowOutcome

    /** The signer remembered a prior rejection; NIP-55 forbids prompting again. */
    data object Rejected : ContentRowOutcome

    /**
     * The signer can't answer in the background (no cursor or a missing value
     * column), so the caller may fall back to the foreground Intent prompt.
     */
    data object Unavailable : ContentRowOutcome
}

/** Interpretation of an `onActivityResult` from the signer app. */
sealed interface ActivityResultOutcome {
    data class PublicKey(
        val pubkey: String,
        val packageName: String?,
    ) : ActivityResultOutcome

    data class Value(
        val value: String,
        val packageName: String?,
    ) : ActivityResultOutcome

    /** RESULT != OK / cancelled — the user declined the prompt. */
    data object Rejected : ActivityResultOutcome

    /** RESULT OK but the expected extra was missing/blank. */
    data class Malformed(
        val reason: String,
    ) : ActivityResultOutcome
}

/**
 * Pure interpretation of a ContentResolver row. `sign_event` yields the SIGNED
 * event JSON from the `event` column; everything else yields the `result`
 * column. A `rejected` flag is terminal; an absent/blank value means "can't
 * answer in the background" and lets the caller fall back to the Intent prompt.
 */
fun parseContentRow(
    op: SignerOp,
    rejected: Boolean,
    resultColumn: String?,
    eventColumn: String?,
): ContentRowOutcome {
    if (rejected) return ContentRowOutcome.Rejected
    val value =
        when (op) {
            SignerOp.SignEvent -> eventColumn
            else -> resultColumn
        }
    return value?.takeIf { it.isNotBlank() }?.let { ContentRowOutcome.Value(it) }
        ?: ContentRowOutcome.Unavailable
}

/**
 * Pure interpretation of a signer `onActivityResult`. `get_public_key` reads the
 * npub/hex from `result` (plus the chosen `package`); `sign_event` reads the
 * SIGNED event JSON from `event`; encrypt/decrypt reads `result`. A non-OK
 * result or an OK result with `rejected=true` is [ActivityResultOutcome.Rejected];
 * an OK result with the expected extra missing is [ActivityResultOutcome.Malformed].
 */
fun parseActivityResult(
    op: SignerOp,
    resultOk: Boolean,
    rejected: Boolean,
    resultExtra: String?,
    eventExtra: String?,
    packageExtra: String?,
): ActivityResultOutcome {
    if (!resultOk || rejected) return ActivityResultOutcome.Rejected
    return when (op) {
        SignerOp.GetPublicKey ->
            resultExtra
                ?.takeIf { it.isNotBlank() }
                ?.let { ActivityResultOutcome.PublicKey(it, packageExtra?.takeIf(String::isNotBlank)) }
                ?: ActivityResultOutcome.Malformed("missing public key")
        SignerOp.SignEvent ->
            eventExtra
                ?.takeIf { it.isNotBlank() }
                ?.let { ActivityResultOutcome.Value(it, packageExtra?.takeIf(String::isNotBlank)) }
                ?: ActivityResultOutcome.Malformed("missing signed event")
        else ->
            resultExtra
                ?.takeIf { it.isNotBlank() }
                ?.let { ActivityResultOutcome.Value(it, packageExtra?.takeIf(String::isNotBlank)) }
                ?: ActivityResultOutcome.Malformed("missing result")
    }
}

internal fun signedEventPubkey(eventJson: String): String? =
    runCatching {
        JSONObject(eventJson).optString("pubkey").takeIf { it.isNotBlank() }
    }.getOrNull()

internal fun signedEventPubkeyMismatchReason(
    eventJson: String,
    expectedPubkey: String,
): String? {
    val pubkey = signedEventPubkey(eventJson) ?: return "signed event missing pubkey"
    return if (pubkey.equals(expectedPubkey, ignoreCase = true)) null else "signed event pubkey mismatch"
}

internal fun signerPackageEchoMismatchReason(
    packageName: String?,
    expectedPackageName: String,
): String? {
    if (packageName.isNullOrBlank()) return null
    return if (packageName == expectedPackageName) null else "signer package mismatch"
}

internal fun trustedSignerPackageFailureReason(
    handledPackageName: String?,
    echoedPackageName: String?,
): String? {
    val handled = handledPackageName?.takeIf { it.isNotBlank() } ?: return "missing handled signer package"
    return signerPackageEchoMismatchReason(echoedPackageName, handled)
}

/** JVM-safe NIP-55 constants and helpers with no Android dependencies. */
object Nip55Pure {
    const val SCHEME = "nostrsigner"

    /**
     * Conservative UTF-8 byte budget for event/content embedded in a NIP-55
     * foreground Intent's `nostrsigner:` data URI.
     */
    const val MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES = 256 * 1024

    const val COLUMN_REJECTED = "rejected"
    const val COLUMN_RESULT = "result"
    const val COLUMN_EVENT = "event"

    /** True when [content] is small enough to embed in a foreground Intent data URI. */
    fun contentFitsIntentFallbackBudget(content: String): Boolean {
        val contentSize = content.toByteArray(Charsets.UTF_8).size
        return contentSize <= MAX_INTENT_FALLBACK_CONTENT_UTF8_BYTES
    }
}
