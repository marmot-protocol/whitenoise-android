package dev.ipf.whitenoise.android.ui.qr

import dev.ipf.whitenoise.android.core.NostrProfileReference
import dev.ipf.whitenoise.android.core.ProfileLink

/**
 * Pure scan-result parsing for [QrScannerSheet] callers. Each [QrScanUseCase]
 * maps a raw QR payload to the navigation or error outcome that surface should
 * take. Npub/nprofile payloads are checksum-validated at this boundary.
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

object QrScanResult {
    private const val NOSTR_URI_PREFIX = "nostr:"
    private val HEX_PUBKEY = Regex("^[0-9a-fA-F]{64}$")

    fun resolve(
        raw: String,
        useCase: QrScanUseCase,
    ): QrScanOutcome =
        when (useCase) {
            QrScanUseCase.ViewProfile -> resolveViewProfile(raw)
            QrScanUseCase.PickRecipient -> resolvePickRecipient(raw)
        }

    private fun resolveViewProfile(raw: String): QrScanOutcome {
        validatedNpub(raw)?.let { return QrScanOutcome.OpenProfileNpub(it) }
        validatedNprofile(raw)?.let { (nprofile, hex) ->
            return QrScanOutcome.OpenProfileNprofile(nprofile, hex)
        }
        return QrScanOutcome.Invalid
    }

    private fun resolvePickRecipient(raw: String): QrScanOutcome {
        validatedNpub(raw)?.let { return QrScanOutcome.FillRecipientQuery(it) }
        validatedNprofile(raw)?.let { (_, hex) ->
            return QrScanOutcome.FillRecipientQuery(hex)
        }
        val trimmed = raw.trim()
        if (HEX_PUBKEY.matches(trimmed)) return QrScanOutcome.FillRecipientQuery(trimmed.lowercase())
        return QrScanOutcome.Invalid
    }

    private fun validatedNpub(raw: String): String? {
        val candidate = ProfileLink.parse(raw)?.npub ?: bech32Candidate(raw, "npub1") ?: return null
        return candidate.takeIf { NostrProfileReference.isValidNpub(candidate) }?.lowercase()
    }

    private fun validatedNprofile(raw: String): Pair<String, String>? {
        val candidate = bech32Candidate(raw, "nprofile1") ?: return null
        val hex = NostrProfileReference.accountIdHex(candidate) ?: return null
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
