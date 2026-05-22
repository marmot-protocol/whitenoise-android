package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Test
import org.marmotprotocol.marmotkit.MarmotEventFfi
import org.marmotprotocol.marmotkit.ReceivedMessageFfi
import org.marmotprotocol.marmotkit.RuntimeMessageReceivedFfi

class DiagnosticFormatterTest {
    @Test
    fun describesMessagesAndGroupUpdates() {
        val message = MarmotEventFfi.MessageReceived(
            RuntimeMessageReceivedFfi(
                accountIdHex = "alice-account",
                accountLabel = "alice",
                message = ReceivedMessageFfi(
                    messageIdHex = "message",
                    groupIdHex = "group",
                    sender = "0123456789abcdef",
                    senderDisplayName = null,
                    plaintext = "hello",
                    appMessage = null,
                ),
            ),
        )
        val group = MarmotEventFfi.GroupStateUpdated(
            accountIdHex = "alice-account",
            accountLabel = "alice",
            groupIdHex = "fedcba9876543210",
        )

        assertEquals("[alice] msg from 01234567...cdef: hello", DiagnosticFormatter.describe(message))
        assertEquals("[alice] group state fedcba98...3210", DiagnosticFormatter.describe(group))
    }
}
