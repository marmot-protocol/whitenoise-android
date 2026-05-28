package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.MarmotEventFfi

object DiagnosticFormatter {
    fun describe(event: MarmotEventFfi): String {
        return when (event) {
            is MarmotEventFfi.GroupJoined ->
                "[${event.accountLabel}] joined group ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.GroupStateUpdated ->
                "[${event.accountLabel}] group state ${IdentityFormatter.short(event.groupIdHex)}"
            is MarmotEventFfi.MessageReceived ->
                "[${event.received.accountLabel}] msg from ${IdentityFormatter.short(event.received.message.sender)}: ${event.received.message.plaintext}"
            is MarmotEventFfi.ProjectionUpdated ->
                "[${event.update.accountLabel}] projection ${IdentityFormatter.short(event.update.update.groupIdHex)} (${event.update.update.messages.size} messages)"
            is MarmotEventFfi.GroupEvent ->
                "[${event.accountLabel}] group event"
            is MarmotEventFfi.AccountError ->
                "[${event.accountLabel}] error: ${event.message}"
            is MarmotEventFfi.AgentStreamActivity ->
                "[${event.accountLabel}] agent stream activity"
        }
    }
}
