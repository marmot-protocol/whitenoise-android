package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarmotEventFfi

object DiagnosticFormatter {
    fun describe(event: MarmotEventFfi): String =
        when (event) {
            is MarmotEventFfi.GroupJoined ->
                "[${event.accountLabel}] joined group ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.GroupStateUpdated ->
                "[${event.accountLabel}] group state ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.MessageReceived -> {
                // Diagnostic entries render live on the Diagnostics screen and
                // are captured by screen recorders, screenshots, and logcat.
                // Never embed decrypted message text here — the kind + length
                // is enough to debug delivery without breaking the e2e
                // confidentiality contract.
                val msg = event.received.message
                "[${event.received.accountLabel}] msg from ${IdentityFormatter.short(msg.sender)} " +
                    "kind=${msg.kind} len=${msg.plaintext.length}"
            }
            is MarmotEventFfi.ProjectionUpdated ->
                "[${event.update.accountLabel}] projection ${IdentityFormatter.short(
                    event.update.update.groupIdHex,
                )} (${event.update.update.messages.size} messages)"
            is MarmotEventFfi.GroupEvent ->
                "[${event.accountLabel}] group event"
            is MarmotEventFfi.AccountError ->
                // The FFI error string is not guaranteed content-free; scrub
                // common secret shapes before truncating so a path that ever
                // interpolates a relay URL, token, key, or decrypted content
                // can't leak it in full through this screen-capturable surface.
                "[${event.accountLabel}] error: ${redactError(event.message)}"
            is MarmotEventFfi.AgentStreamActivity ->
                "[${event.accountLabel}] agent stream activity"
        }

    private const val MAX_ERROR_LEN = 80
    private const val REDACTED = "[redacted]"
    private val NSEC_SECRET = Regex("\\bnsec1[0-9a-zA-Z]+\\b")
    private val HEX_SECRET = Regex("\\b[0-9a-fA-F]{64,}\\b")
    private val CREDENTIALS_IN_URL = Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@")
    private val TOKEN_ASSIGNMENT = Regex("(?i)\\b(authorization|bearer|token|auth[_-]?token|password|secret)=([^\\s&]+)")

    private fun redactError(message: String): String {
        val scrubbed =
            message
                .replace(CREDENTIALS_IN_URL) { "${it.groupValues[1]}$REDACTED@" }
                .replace(NSEC_SECRET, REDACTED)
                .replace(HEX_SECRET, REDACTED)
                .replace(TOKEN_ASSIGNMENT) { "${it.groupValues[1]}=$REDACTED" }
        if (scrubbed.length <= MAX_ERROR_LEN) return scrubbed
        // Don't truncate mid surrogate pair — that would leave a lone surrogate.
        val end = if (Character.isHighSurrogate(scrubbed[MAX_ERROR_LEN - 1])) MAX_ERROR_LEN - 1 else MAX_ERROR_LEN
        return scrubbed.take(end) + "…"
    }
}
