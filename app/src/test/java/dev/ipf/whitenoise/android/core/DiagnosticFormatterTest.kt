package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.ReceivedMessageFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFormatterTest {
    @Test
    fun describesMessagesAndGroupUpdates() {
        val message =
            MarmotEventFfi.MessageReceived(
                RuntimeMessageReceivedFfi(
                    accountIdHex = "alice-account",
                    accountLabel = "alice",
                    message =
                        ReceivedMessageFfi(
                            messageIdHex = "message",
                            groupIdHex = "group",
                            sender = "0123456789abcdef",
                            senderDisplayName = null,
                            plaintext = "hello",
                            contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
                            kind = 9uL,
                            tags = emptyList(),
                            recordedAt = 1_779_926_400uL,
                        ),
                ),
            )
        val group =
            MarmotEventFfi.GroupStateUpdated(
                accountIdHex = "alice-account",
                accountLabel = "alice",
                groupIdHex = "fedcba9876543210",
            )
        val projection =
            MarmotEventFfi.ProjectionUpdated(
                RuntimeProjectionUpdateFfi(
                    accountIdHex = "alice-account",
                    accountLabel = "alice",
                    update =
                        TimelineProjectionUpdateFfi(
                            groupIdHex = "aaaabbbbccccdddd",
                            messages = emptyList(),
                            changes = emptyList(),
                            chatListRow = null,
                            chatListTrigger = ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH,
                        ),
                ),
            )

        assertEquals(
            "[alice] msg from 01234567...cdef kind=9 len=5",
            DiagnosticFormatter.describe(message),
        )
        assertEquals("[alice] group state fedcba98...3210", DiagnosticFormatter.describe(group))
        assertEquals("[alice] projection aaaabbbb...dddd (0 messages)", DiagnosticFormatter.describe(projection))
    }

    @Test
    fun accountErrorsScrubSecretsBeforeTruncating() {
        val secretHex = "a".repeat(64)
        val longHex = "b".repeat(128)
        val event =
            MarmotEventFfi.AccountError(
                accountIdHex = "alice-account",
                accountLabel = "alice",
                message =
                    "failed nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq " +
                        "token=abc123 https://user:pass@example.test $secretHex $longHex " +
                        "while syncing a verbose diagnostics payload that should be truncated",
            )
        val described = DiagnosticFormatter.describe(event)

        assertTrue(described.startsWith("[alice] error: failed [redacted] token=[redacted] https://[redacted]@example.test"))
        assertTrue(described.endsWith("…"))
        assertFalse(described.contains("nsec1"))
        assertFalse(described.contains("abc123"))
        assertFalse(described.contains("user:pass"))
        assertFalse(described.contains(secretHex))
        assertFalse(described.contains(longHex))
    }
}
