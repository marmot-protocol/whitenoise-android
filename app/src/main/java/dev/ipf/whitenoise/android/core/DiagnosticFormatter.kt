package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarmotEventFfi

data class DiagnosticIdentityPresentation(
    val accountLabel: (accountLabel: String, accountIdHex: String) -> String,
    val publicIdentity: (pubkeyHex: String) -> String,
) {
    companion object {
        fun accountLabel(
            accountLabel: String,
            accountIdHex: String,
            presentPublicIdentity: (String) -> String,
        ): String {
            val label = accountLabel.takeIf { it.isNotBlank() }
            if (label != null && !IdentityFormatter.isNostrIdentityFallback(label)) {
                return label
            }
            return presentPublicIdentity(accountIdHex)
        }
    }
}

object DiagnosticFormatter {
    fun describe(
        event: MarmotEventFfi,
        identity: DiagnosticIdentityPresentation,
    ): String =
        when (event) {
            is MarmotEventFfi.GroupJoined ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] joined group ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.GroupStateUpdated ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] group state ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.MessageReceived -> {
                // Diagnostic entries render live on the Diagnostics screen and
                // are captured by screen recorders, screenshots, and logcat.
                // Never embed decrypted message text here — the kind + length
                // is enough to debug delivery without breaking the e2e
                // confidentiality contract.
                val msg = event.received.message
                val account =
                    identity.accountLabel(
                        event.received.accountLabel,
                        event.received.accountIdHex,
                    )
                "[$account] msg from ${identity.publicIdentity(msg.sender)} " +
                    "kind=${msg.kind} len=${msg.plaintext.length}"
            }
            is MarmotEventFfi.ProjectionUpdated ->
                "[${identity.accountLabel(event.update.accountLabel, event.update.accountIdHex)}] projection ${IdentityFormatter.short(
                    event.update.update.groupIdHex,
                )} (${event.update.update.messages.size} messages)"
            is MarmotEventFfi.GroupEvent ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] group event"
            is MarmotEventFfi.AccountError ->
                // The FFI error string is not guaranteed content-free; scrub
                // common secret shapes before truncating so a path that ever
                // interpolates a relay URL, token, key, or decrypted content
                // can't leak it in full through this screen-capturable surface.
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] error: ${redactError(event.message)}"
            is MarmotEventFfi.AgentStreamActivity ->
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] agent stream activity"
            is MarmotEventFfi.EpochStallEscalated ->
                // hex group id only — epoch and arm count carry no content.
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] epoch stall escalated in ${IdentityFormatter.short(
                    event.groupIdHex,
                )} epoch=${event.stalledEpoch} arms=${event.arms}"
            is MarmotEventFfi.WelcomeDeliveryPending ->
                // Recipient is a public identity; group id stays shortened hex.
                "[${identity.accountLabel(event.accountLabel, event.accountIdHex)}] welcome pending for ${identity.publicIdentity(
                    event.recipientHex,
                )} in group ${IdentityFormatter.short(event.groupIdHex)}"
        }

    private const val MAX_ERROR_LEN = 80
    private const val REDACTED = "[redacted]"
    private val NSEC_SECRET = Regex("(?i)nsec1[0-9a-z]+\\b")
    private val HEX_SECRET = Regex("\\b[0-9a-fA-F]{64,}\\b")
    private val SEPARATED_HEX_SECRET = Regex("(?i)\\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){15,}\\b")

    // No trailing \b after the padding: `=` is a non-word char, so a \b there
    // only matched when another word char followed, letting the engine backtrack
    // to zero `=` and leak the trailing `=`/`==` when the padding ended the token
    // or was followed by whitespace/punctuation.
    private val BASE64_SECRET = Regex("\\b[A-Za-z0-9+/]{40,}={0,2}")
    private val CREDENTIALS_IN_URL = Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@")
    private val TOKEN_ASSIGNMENT = Regex("(?i)\\b(authorization|bearer|token|auth[_-]?token|password|secret)=([^\\s&]+)")
    private val BEARER_HEADER = Regex("(?i)\\bauthorization:\\s*bearer\\s+\\S+")

    /**
     * Scrub secret-shaped substrings (nsec, long hex, URL credentials, token
     * assignments) and clamp length before an FFI error string reaches any
     * user-facing surface. Public so the highest-sensitivity callers (secret-key
     * export / encrypted backup toasts, which are not behind FLAG_SECURE) can
     * reuse the same scrubber instead of presenting raw FFI text (#846).
     */
    fun redactError(message: String): String {
        val scrubbed =
            message
                .replace(CREDENTIALS_IN_URL) { "${it.groupValues[1]}$REDACTED@" }
                .replace(NSEC_SECRET, REDACTED)
                .replace(HEX_SECRET, REDACTED)
                .replace(SEPARATED_HEX_SECRET, REDACTED)
                .replace(BASE64_SECRET, REDACTED)
                .replace(TOKEN_ASSIGNMENT) { "${it.groupValues[1]}=$REDACTED" }
                .replace(BEARER_HEADER, "Authorization: Bearer $REDACTED")
        if (scrubbed.length <= MAX_ERROR_LEN) return scrubbed
        // Don't truncate mid surrogate pair — that would leave a lone surrogate.
        val end = if (Character.isHighSurrogate(scrubbed[MAX_ERROR_LEN - 1])) MAX_ERROR_LEN - 1 else MAX_ERROR_LEN
        return scrubbed.take(end) + "…"
    }
}
