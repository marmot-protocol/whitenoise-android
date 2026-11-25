package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingCheckOutcomeFfi
import dev.ipf.marmotkit.OnboardingCheckPurposeFfi
import dev.ipf.marmotkit.OnboardingFindingContextFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayCapabilityFfi
import dev.ipf.marmotkit.OnboardingRelayRemovalReasonFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRelayTagChangeFfi
import dev.ipf.marmotkit.OnboardingRelayTagDispositionFfi
import dev.ipf.marmotkit.OnboardingRelayTagFfi
import dev.ipf.marmotkit.OnboardingRelayTagRoleFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi

/*
 * Snapshots transcribed from MDK's wire-level relay fault simulator (onboarding_relay_faults.rs) for an imported key
 * whose relay list names one relay answering HTTP 503. Statuses, findings and offered actions are as MDK reported them.
 */

internal const val FLAKY_RELAY = "wss://relay.damus.io"

private val INBOX_DEFAULT_TAG =
    OnboardingRelayTagFfi(
        listOf("relay", "wss://relay.us.whitenoise.chat"),
        "wss://relay.us.whitenoise.chat",
        OnboardingRelayTagRoleFfi.INBOX,
    )

/** Inbox relays stopped: one relay was unreachable, so MDK could not prove the inbox list absent. */
internal fun inboxDiscoveryIncompleteSnapshot(): OnboardingSnapshotFfi =
    failedStep(
        OnboardingStepFfi.INBOX_RELAYS,
        listOf(
            OnboardingActionFfi.RETRY,
            OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
            OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
            OnboardingActionFfi.EDIT_RELAYS,
            OnboardingActionFfi.CANCEL_ONBOARDING,
        ),
        listOf(
            discoveryFinding(OnboardingIssueFfi.DISCOVERY_INCOMPLETE, null),
            discoveryFinding(OnboardingIssueFfi.UNREACHABLE, FLAKY_RELAY),
        ),
    )

/** Save was refused: the relay answered the check but not the freshness re-check, so the proposal was dropped. */
internal fun refusedSaveSnapshot(
    issue: OnboardingIssueFfi = OnboardingIssueFfi.UNREACHABLE,
    purpose: OnboardingCheckPurposeFfi = OnboardingCheckPurposeFfi.SOURCE_FRESHNESS,
): OnboardingSnapshotFfi =
    failedStep(
        OnboardingStepFfi.INBOX_RELAYS,
        listOf(
            OnboardingActionFfi.RETRY,
            OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
            OnboardingActionFfi.CANCEL_ONBOARDING,
        ),
        listOf(
            setupFinding(
                issue,
                FLAKY_RELAY.takeIf { issue != OnboardingIssueFfi.RECORD_CHANGED },
                context = OnboardingFindingContextFfi.REPAIR_APPROVAL,
                purpose = purpose,
                outcome = OnboardingCheckOutcomeFfi.FAILURE,
            ),
        ),
    )

/** An approved inbox change no relay accepted: the approval and proposal stay, only Retry is offered. */
internal fun refusedPublicationSnapshot(): OnboardingSnapshotFfi =
    failedStep(
        OnboardingStepFfi.INBOX_RELAYS,
        listOf(OnboardingActionFfi.RETRY, OnboardingActionFfi.CANCEL_ONBOARDING),
        listOf(
            setupFinding(
                OnboardingIssueFfi.PUBLICATION_FAILED,
                null,
                context = OnboardingFindingContextFfi.REPAIR_PUBLICATION,
                purpose = OnboardingCheckPurposeFfi.EVENT_PUBLICATION,
                outcome = OnboardingCheckOutcomeFfi.FAILURE,
            ),
        ),
    ).copy(
        proposal =
            OnboardingRepairProposalFfi(
                OnboardingStepFfi.INBOX_RELAYS,
                3uL,
                null,
                listOf("wss://relay.us.whitenoise.chat"),
                emptyList(),
                null,
                null,
                relayRepair =
                    relayRepairFixture().copy(
                        beforeTags = emptyList(),
                        afterTags = listOf(INBOX_DEFAULT_TAG),
                        changes =
                            listOf(
                                OnboardingRelayTagChangeFfi(
                                    OnboardingRelayTagDispositionFfi.ADDED,
                                    null,
                                    0uL,
                                    INBOX_DEFAULT_TAG.fields,
                                    INBOX_DEFAULT_TAG.endpoint,
                                    INBOX_DEFAULT_TAG.role,
                                    OnboardingRelayCapabilityFfi.NONE,
                                    OnboardingRelayRemovalReasonFfi.NONE,
                                ),
                            ),
                    ),
            ),
    )

/** Messaging keys could not be published; MDK offers Retry and a discovery-relay change. */
internal fun keyPackageFailureSnapshot(): OnboardingSnapshotFfi =
    failedStep(
        OnboardingStepFfi.KEY_PACKAGE,
        listOf(OnboardingActionFfi.RETRY, OnboardingActionFfi.EDIT_DISCOVERY_RELAYS),
        listOf(setupFinding(OnboardingIssueFfi.PUBLICATION_FAILED, null)),
    )

/**
 * The reported user after the inbox change: earlier steps passed, Read and write relays with the 503 relay recorded,
 * and the device notice carries the same relay.
 */
internal fun passedWithWarningsSnapshot(): OnboardingSnapshotFfi =
    setupSnapshot(
        OnboardingStepFfi.SINGLE_DEVICE,
        listOf(
            OnboardingActionFfi.CONTINUE_ANYWAY,
            OnboardingActionFfi.CANCEL_ONBOARDING,
            OnboardingActionFfi.RETRY,
            OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
        ),
    ).apply {
        steps.first { it.step == OnboardingStepFfi.RELAYS }.findings =
            listOf(
                discoveryFinding(OnboardingIssueFfi.DISCOVERY_INCOMPLETE, null),
                discoveryFinding(OnboardingIssueFfi.UNREACHABLE, FLAKY_RELAY),
            )
        steps.first { it.step == OnboardingStepFfi.SINGLE_DEVICE }.findings =
            listOf(discoveryFinding(OnboardingIssueFfi.UNREACHABLE, FLAKY_RELAY))
    }

private const val AUTH_RELAY = "wss://auth.example"

internal fun passedWithCleanupSnapshot(): OnboardingSnapshotFfi =
    passedWithWarningsSnapshot().apply {
        steps.first { it.step == OnboardingStepFfi.RELAYS }.apply {
            actions = listOf(OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS)
            findings = listOf(setupFinding(OnboardingIssueFfi.AUTHENTICATION_REQUIRED, AUTH_RELAY))
        }
    }

internal fun passedCleanupPreviewSnapshot(): OnboardingSnapshotFfi =
    passedWithCleanupSnapshot()
        .apply {
            steps.first { it.step == OnboardingStepFfi.RELAYS }.actions =
                listOf(OnboardingActionFfi.APPROVE_REPAIR, OnboardingActionFfi.CANCEL_REPAIR)
        }.copy(
            revision = 4uL,
            proposal = relayPreviewSnapshot().proposal!!.copy(relayRepair = removalPreview()),
        )

private fun removalPreview() =
    relayRepairFixture().let { repair ->
        val removed =
            OnboardingRelayTagFfi(listOf("r", AUTH_RELAY), AUTH_RELAY, OnboardingRelayTagRoleFfi.UNMARKED)
        repair.copy(
            mode = OnboardingRelayRepairModeFfi.REMOVAL_ONLY,
            beforeTags = repair.beforeTags + removed,
            changes =
                listOf(
                    OnboardingRelayTagChangeFfi(
                        OnboardingRelayTagDispositionFfi.REMOVED,
                        1uL,
                        null,
                        removed.fields,
                        removed.endpoint,
                        removed.role,
                        OnboardingRelayCapabilityFfi.NONE,
                        OnboardingRelayRemovalReasonFfi.AUTHENTICATION_REQUIRED,
                    ),
                ),
        )
    }

/** The selected step stopped with [findings]; the others are as in [setupSnapshot]. */
internal fun failedStep(
    step: OnboardingStepFfi,
    actions: List<OnboardingActionFfi>,
    findings: List<OnboardingFindingFfi>,
    status: OnboardingStatusFfi = OnboardingStatusFfi.RETRYABLE_FAILURE,
): OnboardingSnapshotFfi =
    setupSnapshot(step, actions).apply {
        steps.first { it.step == step }.apply {
            this.status = status
            this.findings = findings
        }
    }

private fun discoveryFinding(
    issue: OnboardingIssueFfi,
    endpoint: String?,
): OnboardingFindingFfi =
    setupFinding(
        issue,
        endpoint,
        context = OnboardingFindingContextFfi.RECORD_DISCOVERY,
        purpose = OnboardingCheckPurposeFfi.RECORD_LOOKUP,
        outcome = OnboardingCheckOutcomeFfi.FAILURE,
    )
