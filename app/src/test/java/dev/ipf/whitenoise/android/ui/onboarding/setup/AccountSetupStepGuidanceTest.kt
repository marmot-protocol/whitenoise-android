package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each blocked relay or key step pins only actions that can help and says what went wrong and what to do. */
class AccountSetupStepGuidanceTest {
    /** The reported user's inbox stop keeps Try again on screen, with review as the fallback. */
    @Test fun inboxDiscoveryIncompletePinsRetryThenReview() {
        val state = AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot())
        assertEquals(
            listOf(OnboardingActionFfi.RETRY to true, OnboardingActionFfi.USE_RECOMMENDED_RELAYS to false),
            state.decisionButtons(),
        )
        assertEquals(
            AccountSetupStepGuidance(R.string.setup_problem_check_incomplete, R.string.setup_next_retry_then_review),
            state.stepGuidance(),
        )
    }

    /** A refused Save says nothing was published and pins only Try again, never a discovery-relay change. */
    @Test fun refusedSaveExplainsAndPinsOnlyRetry() {
        val state = AccountSetupState(snapshot = refusedSaveSnapshot())
        assertEquals(listOf(OnboardingActionFfi.RETRY to true), state.decisionButtons())
        assertEquals(
            AccountSetupStepGuidance(R.string.setup_problem_save_refused, R.string.setup_next_retry),
            state.stepGuidance(),
        )
    }

    /** A Save refused because the record changed elsewhere names that cause instead. */
    @Test fun refusedSaveAfterRecordChangeNamesTheChange() {
        val state = AccountSetupState(snapshot = refusedSaveSnapshot(OnboardingIssueFfi.RECORD_CHANGED))
        assertEquals(R.string.setup_problem_save_record_changed, state.stepGuidance()?.problem)
    }

    /** A declaration refused before publication keeps Try again without blaming a relay check. */
    @Test fun refusedDeclarationReportsOnlyTheSaveOutcome() {
        val snapshot =
            refusedSaveSnapshot(OnboardingIssueFfi.RECORD_CHANGED, OnboardingCheckPurposeFfi.DECLARATION_VALIDATION)
        val state = AccountSetupState(snapshot = snapshot)
        assertEquals(listOf(OnboardingActionFfi.RETRY to true), state.decisionButtons())
        assertEquals(
            AccountSetupStepGuidance(R.string.setup_problem_save_not_published, R.string.setup_next_retry),
            state.stepGuidance(),
        )
    }

    /** An approved change whose publication did not finish keeps the pending notice and pins only Try again. */
    @Test fun refusedPublicationPinsOnlyRetry() {
        val state = AccountSetupState(snapshot = refusedPublicationSnapshot())
        assertEquals(listOf(OnboardingActionFfi.RETRY to true), state.decisionButtons())
        assertEquals(
            AccountSetupStepGuidance(R.string.setup_problem_publication_failed, R.string.setup_next_retry),
            state.stepGuidance(),
        )
    }

    /** Messaging keys that failed to publish offer Try again, not another discovery relay. */
    @Test fun keyPackageFailurePinsOnlyRetry() {
        val state = AccountSetupState(snapshot = keyPackageFailureSnapshot())
        assertEquals(listOf(OnboardingActionFfi.RETRY to true), state.decisionButtons())
        assertEquals(
            AccountSetupStepGuidance(R.string.setup_problem_keys_failed, R.string.setup_next_retry),
            state.stepGuidance(),
        )
    }

    /** A missing list keeps the publishing review first, with nothing published before Save. */
    @Test fun missingListPinsReviewThenEdit() {
        val snapshot =
            failedStep(
                OnboardingStepFfi.RELAYS,
                listOf(
                    OnboardingActionFfi.RETRY,
                    OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
                    OnboardingActionFfi.EDIT_RELAYS,
                ),
                listOf(setupFinding(OnboardingIssueFfi.MISSING, null)),
                status = OnboardingStatusFfi.NEEDS_INPUT,
            )
        val state = AccountSetupState(snapshot = snapshot)
        assertEquals(
            listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS to true, OnboardingActionFfi.EDIT_RELAYS to false),
            state.decisionButtons(),
        )
        assertEquals(
            AccountSetupStepGuidance(null, R.string.setup_next_review),
            state.stepGuidance(),
        )
    }

    /** Removing unusable relays is pinned when MDK offers it, without a second button opening the same preview. */
    @Test fun removeUnusableRelaysIsPinnedOnce() {
        val snapshot =
            failedStep(
                OnboardingStepFfi.RELAYS,
                listOf(
                    OnboardingActionFfi.RETRY,
                    OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
                    OnboardingActionFfi.EDIT_RELAYS,
                    OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS,
                ),
                listOf(setupFinding(OnboardingIssueFfi.AUTHENTICATION_REQUIRED, "wss://auth.example")),
                status = OnboardingStatusFfi.NEEDS_INPUT,
            )
        val state = AccountSetupState(snapshot = snapshot)
        assertEquals(
            listOf(OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS to true, OnboardingActionFfi.EDIT_RELAYS to false),
            state.decisionButtons(),
        )
        assertEquals(R.string.setup_next_remove_unusable, state.stepGuidance()?.next)
    }

    /** A failed check that also offers removal keeps Try again beside it. */
    @Test fun failedCheckKeepsRetryBesideRemoval() {
        val snapshot =
            failedStep(
                OnboardingStepFfi.RELAYS,
                listOf(OnboardingActionFfi.RETRY, OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS),
                listOf(setupFinding(OnboardingIssueFfi.PAYMENT_REQUIRED, "wss://paid.example")),
            )
        assertEquals(
            listOf(OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS to true, OnboardingActionFfi.RETRY to false),
            AccountSetupState(snapshot = snapshot).decisionButtons(),
        )
    }

    /** No state pins a discovery-relay change, whatever else MDK offers beside it. */
    @Test fun discoveryRelayChangeIsNeverPinned() {
        val snapshots =
            listOf(
                inboxDiscoveryIncompleteSnapshot(),
                refusedSaveSnapshot(),
                refusedPublicationSnapshot(),
                keyPackageFailureSnapshot(),
                passedWithWarningsSnapshot(),
                setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.EDIT_DISCOVERY_RELAYS)),
            ) +
                OnboardingStatusFfi.entries.map { status ->
                    failedStep(
                        OnboardingStepFfi.INBOX_RELAYS,
                        listOf(OnboardingActionFfi.RETRY, OnboardingActionFfi.EDIT_DISCOVERY_RELAYS),
                        emptyList(),
                        status,
                    )
                }
        snapshots.forEach { snapshot ->
            val pinned = AccountSetupState(snapshot = snapshot).decisionButtons().map { it.first }
            assertTrue("$pinned", OnboardingActionFfi.EDIT_DISCOVERY_RELAYS !in pinned)
        }
    }

    /** The device notice after the inbox change pins only its acknowledgement. */
    @Test fun deviceNoticeWithRelayFindingPinsOnlyContinue() {
        assertEquals(
            listOf(OnboardingActionFfi.CONTINUE_ANYWAY to true),
            AccountSetupState(snapshot = passedWithWarningsSnapshot()).decisionButtons(),
        )
    }

    /** A proposal under review, a signer prompt and a stopped screen already explain themselves. */
    @Test fun reviewSignerAndFailureScreensGetNoGuidance() {
        assertNull(AccountSetupState(snapshot = relayPreviewSnapshot()).stepGuidance())
        val signer =
            failedStep(
                OnboardingStepFfi.KEY_PACKAGE,
                listOf(OnboardingActionFfi.RECONNECT_SIGNER, OnboardingActionFfi.RETRY),
                listOf(setupFinding(OnboardingIssueFfi.SIGNER_UNAVAILABLE, null)),
                OnboardingStatusFfi.WAITING_FOR_SIGNER,
            )
        assertNull(AccountSetupState(snapshot = signer).stepGuidance())
        assertNull(AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot(), error = true).stepGuidance())
        assertNull(AccountSetupState(snapshot = setupSnapshot()).stepGuidance())
    }

    /** Every relay and key step has a purpose sentence; profile, follows and device carry their own copy. */
    @Test fun purposeCoversRelayAndKeySteps() {
        val explained = OnboardingStepFfi.entries.filter { setupStepPurpose(it) != null }
        assertEquals(
            listOf(OnboardingStepFfi.RELAYS, OnboardingStepFfi.INBOX_RELAYS, OnboardingStepFfi.KEY_PACKAGE),
            explained,
        )
    }

    /** A passed step lists only named relay problems; an unfinished-discovery note is not a relay problem. */
    @Test fun passedWarningsListNamedRelayProblems() {
        val snapshot = passedWithWarningsSnapshot()
        val relays = snapshot.steps.first { it.step == OnboardingStepFfi.RELAYS }
        assertEquals(
            listOf(OnboardingIssueFfi.UNREACHABLE to FLAKY_RELAY),
            relays.passedWarnings().map { it.issue to it.endpoint },
        )
        val current = snapshot.steps.first { it.step == OnboardingStepFfi.SINGLE_DEVICE }
        assertTrue(current.passedWarnings().isEmpty())
    }

    /** Automatic retry targets only a failed check with nothing waiting for the user. */
    @Test fun connectivityRetryOnlyForAnIdleFailedCheck() {
        val failed = AccountSetupState(snapshot = inboxDiscoveryIncompleteSnapshot())
        assertEquals(
            SetupRequest(3uL, OnboardingStepFfi.INBOX_RELAYS, OnboardingActionFfi.RETRY),
            failed.retryableCheckRequest(),
        )
        val waiting: List<OnboardingSnapshotFfi> =
            listOf(
                refusedPublicationSnapshot(),
                relayPreviewSnapshot(),
                inboxDiscoveryIncompleteSnapshot().copy(cancellationPending = true),
                setupSnapshot(OnboardingStepFfi.RELAYS, listOf(OnboardingActionFfi.RETRY)),
            )
        waiting.forEach { assertNull(AccountSetupState(snapshot = it).retryableCheckRequest()) }
        assertNull(failed.copy(busy = true).retryableCheckRequest())
        assertNull(failed.copy(staleDecision = true).retryableCheckRequest())
    }

    /** Only failure evidence for this saved approval can replace an exception from that command. */
    @Test fun refusedApprovalUsesTheMatchingNativeCheckpoint() {
        val failure = refusedSaveSnapshot().copy(revision = 4uL, recoveryEpoch = "epoch")
        val request =
            SetupRequest(
                3uL,
                OnboardingStepFfi.INBOX_RELAYS,
                OnboardingActionFfi.APPROVE_REPAIR,
                recoveryEpoch = "epoch",
            )
        assertTrue(failure.reportsFailedApproval(SETUP_TEST_ACCOUNT, request))
        assertTrue(!failure.reportsFailedApproval(SETUP_TEST_ACCOUNT, request.copy(recoveryEpoch = "other")))
        val pending = failure.copy(proposal = refusedPublicationSnapshot().proposal)
        assertTrue(!pending.reportsFailedApproval(SETUP_TEST_ACCOUNT, request))
    }
}
