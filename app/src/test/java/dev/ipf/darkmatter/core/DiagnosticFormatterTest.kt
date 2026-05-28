package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Test
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.ReceivedMessageFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi

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
                    kind = 9uL,
                    tags = emptyList(),
                ),
            ),
        )
        val group = MarmotEventFfi.GroupStateUpdated(
            accountIdHex = "alice-account",
            accountLabel = "alice",
            groupIdHex = "fedcba9876543210",
        )
        val projection = MarmotEventFfi.ProjectionUpdated(
            RuntimeProjectionUpdateFfi(
                accountIdHex = "alice-account",
                accountLabel = "alice",
                update = TimelineProjectionUpdateFfi(
                    groupIdHex = "aaaabbbbccccdddd",
                    messages = emptyList(),
                    chatListRow = null,
                ),
            ),
        )

        assertEquals("[alice] msg from 01234567...cdef: hello", DiagnosticFormatter.describe(message))
        assertEquals("[alice] group state fedcba98...3210", DiagnosticFormatter.describe(group))
        assertEquals("[alice] projection aaaabbbb...dddd (0 messages)", DiagnosticFormatter.describe(projection))
    }
}
