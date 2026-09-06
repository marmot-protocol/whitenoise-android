package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ReceivedMessageFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.marmotkit.RuntimeProjectionUpdateFfi
import dev.ipf.marmotkit.TimelineProjectionUpdateFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFormatterTest {
    /** Copies only allowlisted busy subtypes, even when native errors contain private payloads. */
    @Test
    fun busyReportIdentifiesContentionWithoutCopyingNativeMessages() {
        val failures =
            listOf(
                MarmotKitException.AccountWorkerBusy() to "ACCOUNT_WORKER_BUSY",
                MarmotKitException.RuntimeBusy() to "RUNTIME_BUSY",
                MarmotKitException.AccountSessionBusy() to "ACCOUNT_SESSION_BUSY",
                MarmotKitException.StorageBusy("private database path") to "STORAGE_BUSY",
                MarmotKitException.GroupSendQueueFull("private group identifier") to "GROUP_SEND_QUEUE_FULL",
            )
        failures.forEach { (failure, subtype) ->
            val report =
                DiagnosticFormatter.errorReport(
                    "GROUP_INVITE_ACCEPT",
                    IllegalStateException("private wrapper", failure),
                    DiagnosticFormatter.ErrorReportContext("test", "17", "now"),
                )
            assertTrue(report.contains("error=RESOURCE_BUSY"))
            assertTrue(report.contains("detail=contention=$subtype"))
            assertFalse(report.contains("private"))
        }
    }

    /** Caller-selected details retain precedence over the automatic contention subtype. */
    @Test
    fun explicitTechnicalDetailTakesPrecedenceOverContention() {
        val report =
            DiagnosticFormatter.errorReport(
                "GROUP_INVITE_ACCEPT",
                MarmotKitException.AccountWorkerBusy(),
                DiagnosticFormatter.ErrorReportContext("test", "17", "now"),
                technicalDetail = "stage=LOCAL_CONFIRMATION",
            )
        assertTrue(report.contains("detail=stage=LOCAL_CONFIRMATION"))
        assertFalse(report.contains("contention="))
    }

    private val legacyShortHexIdentity =
        DiagnosticIdentityPresentation(
            accountLabel = { label, _ -> label },
            publicIdentity = IdentityFormatter::short,
        )

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
                            contentTokens =
                                MarkdownDocumentFfi(
                                    truncated = false,
                                    blocks = emptyList(),
                                    blankLinesBefore = ByteArray(0),
                                ),
                            kind = 9uL,
                            tags = emptyList(),
                            sourceEpoch = 0uL,
                            retentionSeconds = null,
                            retentionExpiresAt = null,
                            recordedAt = 1_779_926_400uL,
                            receivedAt = 1_779_926_400uL,
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
            DiagnosticFormatter.describe(message, legacyShortHexIdentity),
        )
        assertEquals("[alice] group state fedcba98...3210", DiagnosticFormatter.describe(group, legacyShortHexIdentity))
        assertEquals(
            "[alice] projection aaaabbbb...dddd (0 messages)",
            DiagnosticFormatter.describe(projection, legacyShortHexIdentity),
        )
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
        val described = DiagnosticFormatter.describe(event, legacyShortHexIdentity)

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

    @Test
    fun errorReportUsesStableCodesWithoutExceptionMessages() {
        val secret = "a".repeat(64)
        val failure =
            IllegalStateException(
                "wrapper",
                java.io.IOException("https://user:pass@example.test token=abc $secret"),
            )

        val report =
            DiagnosticFormatter.errorReport(
                operationCode = "message send",
                throwable = failure,
                context =
                    DiagnosticFormatter.ErrorReportContext(
                        appVersion = "1.2.3 (45)",
                        androidVersion = "16 (API 36)",
                        occurredAtUtc = "2026-08-10T12:00:00Z",
                    ),
            )

        assertTrue(report.contains("operation=MESSAGE_SEND"))
        assertTrue(report.contains("error=CONNECTIVITY"))
        assertFalse(report.contains("IllegalStateException"))
        assertFalse(report.contains("wrapper"))
        assertFalse(report.contains("user:pass"))
        assertFalse(report.contains(secret))
        assertFalse(report.contains("detail="))
        assertTrue(report.length <= 600)
    }

    @Test
    fun errorReportRedactsExplicitTechnicalDetail() {
        val identifier = "123e4567-e89b-42d3-a456-426614174000"
        val report =
            DiagnosticFormatter.errorReport(
                operationCode = "attachment open",
                throwable = IllegalStateException("failed for message $identifier"),
                context = DiagnosticFormatter.ErrorReportContext("dev", "test", "now"),
                technicalDetail = "message $identifier from https://user:pass@example.test token=abc",
            )

        assertFalse(report.contains(identifier))
        assertTrue(report.contains("message [redacted]"))
        assertFalse(report.contains("user:pass"))
        assertTrue(report.contains("token=[redacted]"))
    }

    @Test
    fun errorReportUsesTypedInternalMetadataBeforeGenericCauseCategory() {
        val failure =
            object : java.io.IOException("private provider failure"), DiagnosticErrorMetadata {
                override val diagnosticErrorCode: String = "IO"
                override val diagnosticTechnicalDetail: String = "stage=MEDIASTORE_INSERT"
            }

        val report =
            DiagnosticFormatter.errorReport(
                operationCode = "message attachment save",
                throwable = failure,
                context = DiagnosticFormatter.ErrorReportContext("dev", "test", "now"),
            )

        assertTrue(report.contains("error=IO"))
        assertTrue(report.contains("detail=stage=MEDIASTORE_INSERT"))
        assertFalse(report.contains("private provider failure"))
    }

    @Test
    fun errorReportTruncatesUnboundedDetails() {
        val report =
            DiagnosticFormatter.errorReport(
                operationCode = "very long operation ${"x".repeat(200)}",
                throwable = RuntimeException("failure ${"y".repeat(2_000)}"),
                context = DiagnosticFormatter.ErrorReportContext("dev", "test", "now"),
                technicalDetail = "bounded ${"z".repeat(2_000)}",
            )

        assertTrue(report.length <= 600)
        assertTrue(report.contains("operation=VERY_LONG_OPERATION_"))
    }

    @Test
    fun typedMarmotFailuresUseStableCategories() {
        val cases =
            listOf(
                MarmotKitException.Publish("relay") to "CONNECTIVITY",
                MarmotKitException.StorageBusy("locked") to "RESOURCE_BUSY",
                MarmotKitException.InvalidIdentity("bad") to "INVALID_INPUT",
                MarmotKitException.UnknownGroup("a".repeat(64)) to "NOT_FOUND",
                MarmotKitException.ExternalSignerRejected() to "CANCELLED",
                MarmotKitException.KeystoreUnavailable("locked") to "PLATFORM_UNAVAILABLE",
                MarmotKitException.Io("disk") to "IO",
                MarmotKitException.EncryptionFailed("cipher") to "CRYPTO_FAILURE",
            )

        cases.forEach { (failure, expected) ->
            assertEquals(expected, DiagnosticFormatter.errorCode(failure))
        }
    }
}
