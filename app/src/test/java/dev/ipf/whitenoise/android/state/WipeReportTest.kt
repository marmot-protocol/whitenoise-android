package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupLeaveFailureFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.RelayFailureFfi
import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WipeReportTest {
    private fun outcome(
        groupsLeft: UInt = 0u,
        groupLeaveFailures: List<GroupLeaveFailureFfi> = emptyList(),
        keyPackagesDeleted: UInt = 0u,
        keyPackageFailures: List<RelayFailureFfi> = emptyList(),
        localCompleted: Boolean = true,
        localReason: String? = null,
    ): WipeOutcomeFfi =
        WipeOutcomeFfi(
            groupsLeft = groupsLeft,
            groupLeaveFailures = groupLeaveFailures,
            keyPackagesDeleted = keyPackagesDeleted,
            keyPackageFailures = keyPackageFailures,
            localCleanup = LocalCleanupReportFfi(completed = localCompleted, reason = localReason),
        )

    @Test
    fun mapsStagesOneToOneInEngineExecutionOrder() {
        val report =
            wipeReport(
                outcome(
                    groupsLeft = 3u,
                    keyPackagesDeleted = 2u,
                ),
            )
        assertEquals(
            listOf(WipeStage.LeavingGroups, WipeStage.DeletingKeyPackages, WipeStage.WipingLocalData),
            report.stages.map { it.stage },
        )
        assertEquals(3, report.stages[0].completedCount)
        assertEquals(2, report.stages[1].completedCount)
        // The local wipe is all-or-nothing; it has no per-item count.
        assertNull(report.stages[2].completedCount)
    }

    @Test
    fun cleanOutcomeHasNoIssues() {
        val report = wipeReport(outcome(groupsLeft = 1u, keyPackagesDeleted = 4u))
        assertTrue(report.clean)
        assertEquals(0, report.issueCount)
        assertTrue(report.stages.none { it.hasIssues })
    }

    @Test
    fun sumsIssuesAcrossAllStages() {
        val report =
            wipeReport(
                outcome(
                    groupLeaveFailures =
                        listOf(
                            GroupLeaveFailureFfi(groupIdHex = "aa".repeat(32), reason = "relay timed out"),
                            GroupLeaveFailureFfi(groupIdHex = "bb".repeat(32), reason = "commit rejected"),
                        ),
                    keyPackageFailures =
                        listOf(RelayFailureFfi(eventIdHex = "cc".repeat(32), reason = "relay unreachable")),
                    localCompleted = false,
                    localReason = "db locked",
                ),
            )
        assertFalse(report.clean)
        assertEquals(4, report.issueCount)
        assertEquals(listOf(2, 1, 1), report.stages.map { it.failures.size })
    }

    @Test
    fun failureRowsCarryShortenedSubjectAndEngineReason() {
        val report =
            wipeReport(
                outcome(
                    groupLeaveFailures = listOf(GroupLeaveFailureFfi(groupIdHex = "ab".repeat(32), reason = "left behind")),
                    keyPackageFailures = listOf(RelayFailureFfi(eventIdHex = "cd".repeat(32), reason = "gone")),
                ),
            )
        val groupFailure = report.stages[0].failures.single()
        assertEquals("ababababab" + "ab…", groupFailure.subject)
        assertEquals("left behind", groupFailure.reason)
        val relayFailure = report.stages[1].failures.single()
        assertEquals("cdcdcdcdcd" + "cd…", relayFailure.subject)
        assertEquals("gone", relayFailure.reason)
    }

    @Test
    fun failureReasonsAreRedactedBeforeDisplay() {
        val token = "nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val report =
            wipeReport(
                outcome(
                    groupLeaveFailures = listOf(GroupLeaveFailureFfi(groupIdHex = "ab".repeat(32), reason = "failed $token")),
                    keyPackageFailures = listOf(RelayFailureFfi(eventIdHex = "cd".repeat(32), reason = "Authorization: Bearer secret-token")),
                    localCompleted = false,
                    localReason = "password=hunter2",
                ),
            )

        assertEquals(
            "failed [redacted]",
            report.stages[0]
                .failures
                .single()
                .reason,
        )
        assertEquals(
            "Authorization: Bearer [redacted]",
            report.stages[1]
                .failures
                .single()
                .reason,
        )
        assertEquals(
            "password=[redacted]",
            report.stages[2]
                .failures
                .single()
                .reason,
        )
    }

    @Test
    fun incompleteLocalCleanupBecomesASubjectlessFailure() {
        val report = wipeReport(outcome(localCompleted = false, localReason = "sqlcipher busy"))
        val localStage = report.stages.single { it.stage == WipeStage.WipingLocalData }
        assertTrue(localStage.hasIssues)
        val failure = localStage.failures.single()
        assertNull(failure.subject)
        assertEquals("sqlcipher busy", failure.reason)
    }

    @Test
    fun missingLocalCleanupReasonRendersEmptyNotNull() {
        val report = wipeReport(outcome(localCompleted = false, localReason = null))
        val failure = report.stages[2].failures.single()
        assertEquals("", failure.reason)
    }
}

class ShortWipeSubjectTest {
    @Test
    fun shortensLongHexIdsToTwelveCharsPlusEllipsis() {
        assertEquals("0123456789ab…", shortWipeSubject("0123456789abcdef".repeat(4)))
    }

    @Test
    fun keepsShortIdsIntact() {
        assertEquals("abcdef", shortWipeSubject("abcdef"))
    }
}

class SignOutCompletionTest {
    private fun signOutOutcomeFfi(
        keyPackagesDeleted: UInt = 0u,
        keyPackageFailures: List<RelayFailureFfi> = emptyList(),
    ): SignOutOutcomeFfi =
        SignOutOutcomeFfi(
            keyPackagesDeleted = keyPackagesDeleted,
            keyPackageFailures = keyPackageFailures,
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )

    @Test
    fun cleanEngineOutcomeCompletes() {
        assertEquals(SignOutCompletion.Complete, signOutCompletion(signOutOutcomeFfi(keyPackagesDeleted = 3u)))
    }

    @Test
    fun engineCallFailureStillSignsOutLocallyButFlagsRelayRetry() {
        // #349: an FFI failure (relay unreachable, runtime error) must not
        // abort the local sign-out — the mapping reports it as a pending
        // relay cleanup so the UI shows the "will retry on next sign-in" hint.
        assertEquals(SignOutCompletion.RelayCleanupPending, signOutCompletion(null))
    }

    @Test
    fun perRelayKeyPackageFailuresFlagRelayRetry() {
        val outcome =
            signOutOutcomeFfi(
                keyPackagesDeleted = 1u,
                keyPackageFailures = listOf(RelayFailureFfi(eventIdHex = "ee".repeat(32), reason = "timeout")),
            )
        assertEquals(SignOutCompletion.RelayCleanupPending, signOutCompletion(outcome))
    }
}
