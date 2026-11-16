package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ModerationReportOriginFfi
import dev.ipf.marmotkit.ModerationReportOutcomeFfi
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** A report to the White Noise team sends only what MDK documents and tells the reader truthfully how it ended. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModerationReportsTest {
    /** Availability stays unknown after a failed read, and one answer from MDK holds without asking again. */
    @Test
    fun availabilityIsReadOnceAndOnlyFromAnAnswer() =
        runBlocking {
            var reads = 0
            var fail = true
            val state =
                appState { name, _ ->
                    if (name != "moderationReportingAvailable") error("Unexpected native call: $name")
                    reads++
                    if (fail) throw MarmotKitException.ModerationReportingNotConfigured()
                    true
                }

            state.loadTeamReporting()
            assertFalse(state.teamReportingAvailable)
            fail = false
            state.loadTeamReporting()
            state.loadTeamReporting()

            assertTrue(state.teamReportingAvailable)
            assertEquals(2, reads)
        }

    /** The report carries the reported key, the reason and the trimmed, bounded explanation as a plain Report. */
    @Test
    fun aReportSendsOnlyTheKeyReasonAndBoundedExplanation() =
        runBlocking {
            val sent = mutableListOf<List<Any?>>()
            val state =
                appState { name, arguments ->
                    assertEquals("submitModerationReport", name)
                    sent += arguments.dropLast(1)
                    ModerationReportOutcomeFfi("report", SendAcceptDispositionFfi.PUBLISHED)
                }
            val explanation = "  " + "x".repeat(REPORT_EXPLANATION_LIMIT + 5) + "  "

            val outcome = state.reportToTeam(ACCOUNT, REPORTED, ReportReasonFfi.SPAM, explanation)

            assertEquals(TeamReportOutcome.Accepted(R.string.report_team_sent), outcome)
            val expected = "x".repeat(REPORT_EXPLANATION_LIMIT)
            assertEquals(
                listOf(listOf(ACCOUNT, REPORTED, ReportReasonFfi.SPAM, expected, ModerationReportOriginFfi.REPORT)),
                sent,
            )
        }

    /** Only a relay's acceptance reads as sent; a queued or unconfirmed report says it is still on its way. */
    @Test
    fun eachDispositionSaysWhetherARelayHasIt() {
        assertEquals(R.string.report_team_sent, teamReportAcceptedMessage(SendAcceptDispositionFfi.PUBLISHED))
        assertEquals(R.string.report_team_queued, teamReportAcceptedMessage(SendAcceptDispositionFfi.ACCEPTED_PENDING))
        assertEquals(
            R.string.report_team_pending,
            teamReportAcceptedMessage(SendAcceptDispositionFfi.COMPLETION_UNKNOWN),
        )
    }

    /** MDK's hourly limit says to try later, and any other failure asks for a retry. */
    @Test
    fun aRefusedReportSaysWhy() =
        runBlocking {
            var failure: Throwable = MarmotKitException.ModerationReportRateLimited()
            val state = appState(submit = { throw failure }) { name, _ -> error("Unexpected native call: $name") }

            assertEquals(
                TeamReportOutcome.Failed(R.string.report_team_rate_limited),
                state.reportToTeam(ACCOUNT, REPORTED, ReportReasonFfi.OTHER, ""),
            )
            failure = MarmotKitException.CannotReportSelf()
            assertEquals(
                TeamReportOutcome.Failed(R.string.report_team_failed),
                state.reportToTeam(ACCOUNT, REPORTED, ReportReasonFfi.OTHER, ""),
            )
        }

    /**
     * An app state whose MDK answers each call through [onCall], with its coroutine continuation left off. A [submit]
     * replaces the report call directly, because a reflective proxy would wrap the typed exception it throws.
     */
    private fun appState(
        submit: (suspend () -> ModerationReportOutcomeFfi)? = null,
        onCall: (String, Array<out Any?>) -> Any?,
    ): WhiteNoiseAppState {
        val type = MarmotInterface::class.java
        val proxy =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "toString" -> "ModerationReportsTestRuntime"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> onCall(method.name, arguments.orEmpty())
                }
            } as MarmotInterface
        val marmot =
            submit?.let { report ->
                object : MarmotInterface by proxy {
                    override suspend fun submitModerationReport(
                        accountRef: String,
                        reportedPubkey: String,
                        reason: ReportReasonFfi,
                        explanation: String,
                        origin: ModerationReportOriginFfi,
                    ): ModerationReportOutcomeFfi = report()
                }
            } ?: proxy
        val context = RuntimeEnvironment.getApplication().applicationContext
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { it },
            accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT, true, false, false, true, false)),
            activeAccountRef = ACCOUNT,
            initialMarmotRuntime = AppMarmotRuntime("moderation-test", marmot),
        )
    }

    private companion object {
        const val ACCOUNT = "account-a"
        val REPORTED = "ab".repeat(32)
    }
}
