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
                "[${event.accountLabel}] error: ${event.message}"
            is MarmotEventFfi.AgentStreamActivity ->
                "[${event.accountLabel}] agent stream activity"
        }
}
