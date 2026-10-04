package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PollVoteFailureAttributionTest {
    @Test
    fun knownNativeRejectionsKeepTheirCategoryAndReportOnlyAStaticReason() {
        val reasons =
            mapOf(
                "poll response requires a valid locally accepted poll in this group" to "POLL_UNAVAILABLE",
                "poll is closed" to "POLL_CLOSED",
                "poll is closed at the response event timestamp" to "POLL_RESPONSE_TIME_REJECTED",
                "poll response contains an invalid selection" to "POLL_INVALID_SELECTION",
                "poll response target must be one lowercase 32-byte hex event id" to "POLL_INVALID_TARGET",
            )
        for ((detail, reason) in reasons) {
            val native = MarmotKitException.Runtime("invalid app message payload: $detail")
            val attributed = native.withPollVoteFailureAttribution()
            val report = report(attributed)

            assertSame(native, attributed.cause)
            assertTrue(report.contains("operation=POLL_VOTE\nerror=UNEXPECTED\nmarmot=Runtime\n"))
            assertTrue(report.contains("detail=poll_vote_reason=$reason\n"))
            assertFalse(report.contains(detail))
        }
    }

    @Test
    fun unknownOrModifiedDetailsStayPrivateAndKeepTheOriginalThrowable() {
        val details =
            listOf(
                "private group and message content",
                "poll is closed",
                "invalid app message payload: poll is closed private-content",
                "invalid app message payload: poll is closed\ndetail=private-content",
                "private-content invalid app message payload: poll is closed",
            )
        for (detail in details) {
            val native = MarmotKitException.Runtime(detail)
            assertSame(native, native.withPollVoteFailureAttribution())
            assertFalse(report(native).contains("detail="))
            assertFalse(report(native).contains("private"))
        }
    }

    @Test
    fun wrappedNativeRejectionRetainsTheNativeVariantWithinTheReportBound() {
        val native = MarmotKitException.Runtime("invalid app message payload: poll is closed")
        val wrapped = (1..6).fold(native as Throwable) { cause, _ -> RuntimeException("private wrapper", cause) }
        val report = report(wrapped.withPollVoteFailureAttribution())
        assertTrue(report.contains("marmot=Runtime"))
        assertTrue(report.contains("detail=poll_vote_reason=POLL_CLOSED"))
        assertFalse(report.contains("private wrapper"))

        val tooDeep = RuntimeException("private wrapper", wrapped)
        assertSame(tooDeep, tooDeep.withPollVoteFailureAttribution())
    }

    @Test
    fun cancellationAndCyclicUnknownCausesRemainUnchanged() {
        val cancellation = CancellationException("private cancellation")
        val native = MarmotKitException.Runtime("invalid app message payload: poll is closed")
        native.initCause(cancellation)
        assertSame(native, native.withPollVoteFailureAttribution())
        assertTrue(report(native).contains("error=CANCELLED"))

        val first = RuntimeException("private first")
        val second = RuntimeException("private second", first)
        first.initCause(second)
        assertSame(first, first.withPollVoteFailureAttribution())
        assertFalse(report(first).contains("detail="))
    }

    @Test
    fun anotherNativeVariantDoesNotAcquirePollValidationMetadata() {
        val native = MarmotKitException.InvalidHex("invalid app message payload: poll is closed")
        assertSame(native, native.withPollVoteFailureAttribution())
        assertEquals("INVALID_INPUT", DiagnosticFormatter.errorCode(native))
        assertFalse(report(native).contains("poll_vote_reason"))
    }

    private fun report(throwable: Throwable) =
        privacySafeErrorPresentation(
            operationCode = "POLL_VOTE",
            throwable = throwable,
            appVersion = "test",
            androidVersion = "test",
            occurredAtUtc = "2026-10-04T00:00:00Z",
        ).report
}
