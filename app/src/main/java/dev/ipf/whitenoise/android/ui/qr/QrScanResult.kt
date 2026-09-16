package dev.ipf.whitenoise.android.ui.qr

import dev.ipf.whitenoise.android.core.ProfileLink

/**
 * Pure scan-result parsing for [QrScannerSheet] callers. Each [QrScanUseCase]
 * maps a raw QR payload to the navigation or error outcome that surface should
 * take. Npub/nprofile payloads are validated by MarmotKit's decoder, injected as [AccountIdHexResolver],
 * so the parser itself stays pure.
 */
enum class QrScanUseCase {
    /** Profile QR sheet and New Message scan-to-chat. */
    ViewProfile,

    /** Contact/group recipient picker. */
    PickRecipient,
}

sealed interface QrScanOutcome {
    data class OpenProfileNpub(
        val npub: String,
    ) : QrScanOutcome

    data class OpenProfileNprofile(
        val nprofile: String,
        val accountIdHex: String,
    ) : QrScanOutcome

    data class FillRecipientQuery(
        val reference: String,
    ) : QrScanOutcome

    data object Invalid : QrScanOutcome
}

/** Decodes an npub or nprofile reference to its hex account id, or null when it is not a valid one. */
typealias AccountIdHexResolver = (String) -> String?

object QrScanResult {
    private const val NOSTR_URI_PREFIX = "nostr:"
    private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")

    fun resolve(
        raw: String,
        useCase: QrScanUseCase,
        accountIdHex: AccountIdHexResolver,
    ): QrScanOutcome =
        when (useCase) {
            QrScanUseCase.ViewProfile -> resolveViewProfile(raw, accountIdHex)
            QrScanUseCase.PickRecipient -> resolvePickRecipient(raw, accountIdHex)
        }

    private fun resolveViewProfile(
        raw: String,
        accountIdHex: AccountIdHexResolver,
    ): QrScanOutcome {
        validatedNpub(raw, accountIdHex)?.let { return QrScanOutcome.OpenProfileNpub(it) }
        validatedNprofile(raw, accountIdHex)?.let { (nprofile, hex) ->
            return QrScanOutcome.OpenProfileNprofile(nprofile, hex)
        }
        return QrScanOutcome.Invalid
    }

    private fun resolvePickRecipient(
        raw: String,
        accountIdHex: AccountIdHexResolver,
    ): QrScanOutcome {
        validatedNpub(raw, accountIdHex)?.let { return QrScanOutcome.FillRecipientQuery(it) }
        validatedNprofile(raw, accountIdHex)?.let { (_, hex) ->
            return QrScanOutcome.FillRecipientQuery(hex)
        }
        val trimmed = raw.trim()
        if (HEX_PUBKEY.matches(trimmed)) return QrScanOutcome.FillRecipientQuery(trimmed.lowercase())
        return QrScanOutcome.Invalid
    }

    private fun validatedNpub(
        raw: String,
        accountIdHex: AccountIdHexResolver,
    ): String? {
        val candidate = ProfileLink.parse(raw)?.npub ?: bech32Candidate(raw, "npub1") ?: return null
        return candidate.takeIf { accountIdHex(candidate) != null }?.lowercase()
    }

    private fun validatedNprofile(
        raw: String,
        accountIdHex: AccountIdHexResolver,
    ): Pair<String, String>? {
        val candidate = bech32Candidate(raw, "nprofile1") ?: return null
        val hex = accountIdHex(candidate) ?: return null
        return candidate.lowercase() to hex
    }

    private fun bech32Candidate(
        raw: String,
        prefix: String,
    ): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val payload =
            if (trimmed.startsWith(NOSTR_URI_PREFIX, ignoreCase = true)) {
                trimmed.drop(NOSTR_URI_PREFIX.length)
            } else {
                trimmed
            }
        return payload.takeIf { it.startsWith(prefix, ignoreCase = true) }
    }
}
