package dev.ipf.darkmatter.core

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
                // The FFI error string is not guaranteed content-free; truncate
                // so a path that ever interpolates a relay URL, token, or
                // decrypted content can't leak it in full through this
                // screen-capturable surface.
                "[${event.accountLabel}] error: ${redactError(event.message)}"
            is MarmotEventFfi.AgentStreamActivity ->
                "[${event.accountLabel}] agent stream activity"
        }

    private const val MAX_ERROR_LEN = 80

    private fun redactError(message: String): String {
        if (message.length <= MAX_ERROR_LEN) return message
        // Don't truncate mid surrogate pair — that would leave a lone surrogate.
        val end = if (Character.isHighSurrogate(message[MAX_ERROR_LEN - 1])) MAX_ERROR_LEN - 1 else MAX_ERROR_LEN
        return message.take(end) + "…"
    }
}
