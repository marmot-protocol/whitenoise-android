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

    @Test
    fun redactError_scrubsBearerAuthorizationHeader() {
        val jwtLike = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N"
        val message = "request failed: Authorization: Bearer $jwtLike while contacting relay"
        val redacted = DiagnosticFormatter.redactError(message)

        assertEquals("request failed: Authorization: Bearer [redacted] while contacting relay", redacted)
        assertFalse(redacted.contains(jwtLike))
    }

    @Test
    fun redactError_scrubsBearerHeaderCaseInsensitively() {
        val token = "abc.def.ghi"
        val message = "error: authorization: bearer $token end"
        val redacted = DiagnosticFormatter.redactError(message)

        assertEquals("error: Authorization: Bearer [redacted] end", redacted)
        assertFalse(redacted.contains(token))
    }

    @Test
    fun redactError_scrubsEmbeddedNsecSeparatedHexAndBase64Secrets() {
        val nsec = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val separatedHex = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
        val base64 = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkw"
        val redacted = DiagnosticFormatter.redactError("failed key$nsec seed=$separatedHex blob=$base64")

        assertFalse(redacted.contains(nsec))
        assertFalse(redacted.contains(separatedHex))
        assertFalse(redacted.contains(base64))
        assertEquals("failed key[redacted] seed=[redacted] blob=[redacted]", redacted)
    }

    @Test
    fun redactError_scrubsBase64PaddingAtBoundaryAndEndOfString() {
        // Regression: the trailing \b after `={0,2}` let the engine backtrack to
        // zero `=` when the padding ended the string or was followed by
        // whitespace/punctuation, leaking the trailing `=`/`==`.
        val padded = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODk="
        val doublePadded = "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3OA=="

        // End of string.
        assertEquals("token [redacted]", DiagnosticFormatter.redactError("token $padded"))
        // Followed by whitespace, then punctuation.
        assertEquals("a [redacted] b", DiagnosticFormatter.redactError("a $doublePadded b"))
        assertEquals("[redacted].", DiagnosticFormatter.redactError("$padded."))
    }
}
