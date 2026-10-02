package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded roster recovery and its privacy-safe diagnostics for a notification-opened group (#2861). */
class GroupRosterReadRecoveryTest {
    private val groupId = "ab".repeat(32)

    /** Readiness and busy failures retry, superseded reads re-read, and everything else settles. */
    @Test
    fun failuresAreClassifiedByTypeOnly() {
        assertEquals(
            GroupRosterReadFailureKind.HYDRATION_PENDING,
            classifyGroupRosterReadFailure(MarmotKitException.GroupHydrationPending(groupId)),
        )
        listOf(
            MarmotKitException.AccountWorkerBusy(),
            MarmotKitException.AccountWorkerResponseTimedOut(),
            MarmotKitException.AccountSessionBusy(),
            MarmotKitException.StorageBusy("locked"),
            MarmotKitException.AccountCatchUp("timeout"),
        ).forEach { assertEquals(GroupRosterReadFailureKind.TRANSIENT, classifyGroupRosterReadFailure(it)) }
        assertEquals(
            GroupRosterReadFailureKind.SUPERSEDED,
            classifyGroupRosterReadFailure(SupersededGroupRosterRead()),
        )
        listOf(
            MarmotKitException.UnknownGroup(groupId),
            MarmotKitException.UnknownAccount("account"),
            IllegalStateException("roster unavailable"),
        ).forEach { assertEquals(GroupRosterReadFailureKind.TERMINAL, classifyGroupRosterReadFailure(it)) }
    }

    /** A blocked transcript backs off through every configured delay, then stops retrying. */
    @Test
    fun blockedTranscriptRetriesAreBoundedWithGrowingBackoff() {
        val delays =
            (0..GROUP_ROSTER_READ_RETRY_DELAYS_MS.size).map {
                groupRosterReadRetryDelayMs(GroupRosterReadFailureKind.TRANSIENT, it, transcriptBlocked = true)
            }
        assertEquals(GROUP_ROSTER_READ_RETRY_DELAYS_MS + listOf<Long?>(null), delays)
        assertEquals(GROUP_ROSTER_READ_RETRY_DELAYS_MS.sorted(), GROUP_ROSTER_READ_RETRY_DELAYS_MS)
        assertTrue("bounded recovery stays short", GROUP_ROSTER_READ_RETRY_DELAYS_MS.sum() <= 10_000L)
        assertNull(groupRosterReadRetryDelayMs(GroupRosterReadFailureKind.TERMINAL, 0, transcriptBlocked = true))
    }

    /** Trusted chrome keeps the single hydration re-read it always had and never retries busy reads. */
    @Test
    fun trustedChromeKeepsTheSingleHydrationRetry() {
        val hydration = GroupRosterReadFailureKind.HYDRATION_PENDING
        assertEquals(750L, groupRosterReadRetryDelayMs(hydration, 0, transcriptBlocked = false))
        assertNull(groupRosterReadRetryDelayMs(hydration, 1, transcriptBlocked = false))
        assertNull(groupRosterReadRetryDelayMs(GroupRosterReadFailureKind.TRANSIENT, 0, transcriptBlocked = false))
        val superseded = GroupRosterReadFailureKind.SUPERSEDED
        assertEquals(0L, groupRosterReadRetryDelayMs(superseded, 0, transcriptBlocked = true))
        assertNull(groupRosterReadRetryDelayMs(superseded, 1, transcriptBlocked = true))
    }

    /** The report names the branch and invariant so a failed read and a wrong roster are distinguishable. */
    @Test
    fun reportSplitsReadFailureFromInvariantFailure() {
        val read =
            GroupRosterBlockReason
                .ReadFailed(GroupRosterReadFailureKind.TRANSIENT, attempts = 4)
                .transcriptRosterReport()
        val wrong =
            GroupRosterBlockReason
                .Inconsistent(GroupRosterInvariant.LOCAL_MEMBER_MISSING)
                .transcriptRosterReport()
        assertTrue(read.startsWith("Operation: CONVERSATION_TRANSCRIPT_ROSTER"))
        assertTrue(read.contains("Branch: read_failed") && read.contains("Failure: transient"))
        assertTrue(read.contains("Reads: 4"))
        assertTrue(wrong.contains("Branch: inconsistent") && wrong.contains("Invariant: local_member_missing"))
        assertFalse(read.contains("inconsistent"))
        val wrongError =
            GroupRosterBlockReason
                .Inconsistent(GroupRosterInvariant.EMPTY_JOINED_ROSTER)
                .transcriptRosterError()
        val readError = (null as GroupRosterBlockReason?).transcriptRosterError()
        assertEquals(
            R.string.error_conversation_membership_inconsistent,
            (wrongError.message as AppText.Resource).resId,
        )
        assertEquals(
            R.string.error_conversation_membership_unavailable,
            (readError.message as AppText.Resource).resId,
        )
    }

    /** The release diagnostic carries gate facts only, never an identifier. */
    @Test
    fun diagnosticCarriesNoIdentifiers() {
        val line =
            groupRosterReadDiagnostic(
                event = "retry",
                kind = GroupRosterReadFailureKind.HYDRATION_PENDING,
                attempt = 1,
                transcriptBlocked = true,
                initialSnapshot = false,
                targetAccountActive = false,
            )
        assertEquals(
            "roster read retry kind=hydration_pending attempt=1 transcript_blocked=true " +
                "initial_snapshot=false target_account_active=false",
            line,
        )
        assertFalse(line.contains(groupId))
    }
}
