@file:Suppress("FunctionNaming") // Compose screen functions use the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSingleDeviceNoticeFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCallout
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Lifecycle-aware route; the process-owned controller survives Activity recreation and signer handoff. */
@Composable
internal fun AccountSetupScreen(
    controller: AccountSetupController,
    randomName: (String?) -> String,
    onLater: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    AccountSetupContent(
        state,
        controller::submit,
        { step, action, revision -> controller.edit(step, action, revision) },
        controller::updateEditor,
        {
            controller.state.value.editor
                ?.let { controller.submit(it.request()) }
        },
        controller::dismissEditor,
        controller::reconnect,
        controller::openChats,
        onLater,
        randomName,
        controller::toggleDetails,
        { step, endpoint, revision, epoch ->
            controller.edit(step, OnboardingActionFfi.EDIT_RELAYS, revision, endpoint, epoch)
        },
    )
}

/** A focused native decision, with diagnostic history available on demand. */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun AccountSetupContent(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
    onEditorChange: (SetupEditor) -> Unit,
    onSaveEditor: () -> Unit,
    onDismissEditor: () -> Unit,
    onReconnect: () -> Unit,
    onOpenChats: () -> Unit,
    onLater: () -> Unit,
    randomName: (String?) -> String,
    onToggleDetails: () -> Unit = {},
    onRemoveRelay: (OnboardingStepFfi, String, ULong, String?) -> Unit = { _, _, _, _ -> },
) {
    val step = state.currentStep
    val snapshot = state.snapshot
    val renderedRevision = snapshot?.revision
    val renderedEpoch = snapshot?.recoveryEpoch
    val fields = state.editor?.let { rememberSetupEditorFields(snapshot?.accountIdHex, it, onEditorChange) }
    val back: () -> Unit = {
        when {
            state.busy -> onLater()
            state.editor != null -> onDismissEditor()
            snapshot != null &&
                step != null &&
                snapshot.proposal?.step == step.step &&
                OnboardingActionFfi.CANCEL_REPAIR in step.actions &&
                OnboardingActionFfi.APPROVE_REPAIR in step.actions ->
                onAction(
                    SetupRequest(
                        checkNotNull(renderedRevision),
                        step.step,
                        OnboardingActionFfi.CANCEL_REPAIR,
                        recoveryEpoch = renderedEpoch,
                    ),
                )
            else -> onLater()
        }
    }
    BackHandler(onBack = back)
    val actions: @Composable ColumnScope.() -> Unit = {
        SetupPinnedActions(
            state,
            fields,
            onAction,
            onEdit,
            onEditorChange,
            onSaveEditor,
            onDismissEditor,
            onReconnect,
            onOpenChats,
            onRemoveRelay,
        )
    }
    SetupPage(stringResource(R.string.setup_title), back, actions.takeIf { state.hasPinnedActions }) {
        SetupStageProgress(state)
        SetupErrors(state, onReconnect)
        when (state.setupSurface()) {
            SetupSurface.Editor -> {
                val editor = checkNotNull(state.editor)
                Text(
                    stringResource(
                        if (editor.action == OnboardingActionFfi.EDIT_PROFILE) {
                            R.string.setup_action_edit_profile
                        } else {
                            R.string.setup_action_edit_relays
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
                SetupEditorContent(editor, checkNotNull(fields), state.busy, randomName, step?.findings.orEmpty())
                state.relayRemovalIssue?.let { issue ->
                    Text(
                        stringResource(
                            if (issue == RelayRemovalIssue.UNSAFE_DECLARATION) {
                                R.string.setup_repair_edit_unavailable
                            } else {
                                R.string.setup_add_relay_before_saving
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            SetupSurface.Ready -> SetupProgress(ready = true)
            SetupSurface.Progress -> {
                SetupProgress()
                if (snapshot != null && step != null) SetupOperationNotice(snapshot, step, showCancellation = true)
            }
            SetupSurface.Decision -> {
                if (step != null) {
                    SetupDecisionHeading(state)
                    SetupDecisionContent(state)
                    SetupDirectRelayRemoval(state)
                    if (snapshot?.proposal?.relayRepair != null &&
                        OnboardingActionFfi.APPROVE_REPAIR in step.actions
                    ) {
                        Text(
                            stringResource(R.string.setup_relay_publication_scope),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        if (snapshot != null && !state.busy && !snapshot.ready) {
            val detailStateId =
                if (state.detailsExpanded) {
                    R.string.onboarding_details_expanded
                } else {
                    R.string.onboarding_details_collapsed
                }
            val detailState = stringResource(detailStateId)
            TextButton(
                onClick = onToggleDetails,
                modifier = Modifier.testTag("setup-details").semantics { stateDescription = detailState },
            ) {
                Text(stringResource(if (state.detailsExpanded) R.string.setup_hide_details else R.string.setup_details))
            }
            if (state.detailsExpanded) {
                SetupDetails(state)
                SetupAlternativeActions(state, onAction, onEdit)
            }
        }
    }
}

/** Names the single exact endpoint the pinned Remove action takes into native review. */
@Composable
private fun SetupDirectRelayRemoval(state: AccountSetupState) {
    val step = state.currentStep
    val endpoint = state.directRemovalEndpoint()
    if (state.snapshot == null || step == null || endpoint == null) return
    Text(endpoint, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
    step.findings
        .filter { it.issue == OnboardingIssueFfi.RETIRED_RELAY && it.endpoint == endpoint }
        .distinct()
        .forEach { SetupFindingEvidence(it) }
}

/** Idle checkpoint inspection remains available even when a saved operation offers no mutation yet. */
internal val AccountSetupState.checklistInspectionStep: OnboardingStepFfi?
    get() = currentStep?.takeIf { !busy }?.step

/**
 * Separates connection failure from a decision that became stale. Resume is pinned with the other actions, except in
 * the editor, where it stays here so the keyboard keeps room for the field being edited.
 */
@Composable
private fun SetupErrors(
    state: AccountSetupState,
    onReconnect: () -> Unit,
) {
    if (state.showsSetupFailure) {
        WhiteNoiseCallout(
            text = stringResource(if (state.staleDecision) R.string.setup_stale else R.string.setup_retry_help),
            modifier = Modifier.testTag("setup-failure"),
            icon = R.drawable.ic_error,
            isError = true,
        )
        if (state.editor != null && state.snapshot?.ready != true) {
            WhiteNoiseOutlinedButton(
                onClick = onReconnect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("setup-resume-checks"),
            ) { Text(stringResource(R.string.setup_resume_checks)) }
        }
    }
}

/** Only native-offered actions are displayed; an empty follow list is never published by this flow. */
@Composable
private fun SetupDecisionContent(state: AccountSetupState) {
    val snapshot = state.snapshot ?: return
    state.currentStep?.let { step ->
        if (step.step == OnboardingStepFfi.PROFILE &&
            snapshot.proposal?.step != step.step &&
            OnboardingActionFfi.CONTINUE_WITHOUT in step.actions
        ) {
            Text(
                stringResource(R.string.setup_optional_profile_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SetupOperationNotice(snapshot, step)
        SetupStepFindings(state, snapshot, step)
        if (step.step == OnboardingStepFfi.SINGLE_DEVICE) {
            SetupSingleDeviceNotice(snapshot.singleDeviceNotice)
        }
        snapshot.proposal?.takeIf { it.step == step.step }?.let { proposal ->
            if (OnboardingActionFfi.APPROVE_REPAIR !in step.actions) {
                step.findings.distinct().forEach { finding ->
                    SetupFindingContent(finding, showTitle = !snapshot.deviceNoticeCovers(step.step, finding))
                }
            }
            SetupProposalContent(proposal, step.findings)
        }
    }
}

@Composable
private fun SetupStepFindings(
    state: AccountSetupState,
    snapshot: OnboardingSnapshotFfi,
    step: OnboardingStepStateFfi,
) {
    if (step.step == OnboardingStepFfi.PROFILE || snapshot.proposal?.step == step.step) return
    val directRemovalEndpoint = state.directRemovalEndpoint()
    val deviceStep = step.step == OnboardingStepFfi.SINGLE_DEVICE
    step.findings
        .distinct()
        .filterNot { finding ->
            val routineDeviceNotice = deviceStep && state.routineDeviceNotice
            val directRemovalFinding =
                directRemovalEndpoint != null &&
                    finding.issue == OnboardingIssueFfi.RETIRED_RELAY &&
                    finding.endpoint == directRemovalEndpoint
            routineDeviceNotice || directRemovalFinding
        }.forEach { finding ->
            SetupFindingContent(finding, showTitle = !snapshot.deviceNoticeCovers(step.step, finding))
        }
}

/** Distinguishes a saved cancellation from an approved publication that must finish before cancellation. */
@Composable
private fun SetupOperationNotice(
    snapshot: OnboardingSnapshotFfi,
    step: OnboardingStepStateFfi,
    showCancellation: Boolean = false,
) {
    if (snapshot.cancellationPending) {
        if (showCancellation) SetupNotice(stringResource(R.string.setup_cancellation_saved_pending))
    } else if (snapshot.proposal?.step == step.step &&
        snapshot.proposal?.relayRepair?.mode != OnboardingRelayRepairModeFfi.MANUAL_REVIEW &&
        OnboardingActionFfi.APPROVE_REPAIR !in step.actions
    ) {
        SetupNotice(stringResource(R.string.setup_approval_saved_pending))
    }
}

/** State the discovery once, then explain what the user can do with their other devices. */
@Composable
private fun SetupSingleDeviceNotice(notice: OnboardingSingleDeviceNoticeFfi?) {
    val evidence = setupDeviceEvidence(notice)
    evidence?.let { SetupNotice(setupDeviceFinding(it)) }
    setupDeviceExplanation(evidence).forEach { Text(stringResource(it)) }
}

/** Shows usable relay routes and explains recommendation preservation versus custom replacement. */
@Composable
private fun SetupProposalContent(
    proposal: OnboardingRepairProposalFfi,
    findings: List<OnboardingFindingFfi>,
) {
    if (proposal.step == OnboardingStepFfi.PROFILE) {
        Text(stringResource(R.string.setup_profile_scope_help))
        proposal.profile?.let { profile ->
            EmojiLabel(profile.displayName.orEmpty())
            EmojiLabel(profile.about.orEmpty())
        }
    } else if (proposal.relayRepair != null) {
        // MDK built this on a check some source never answered, so approving it is the user's informed risk.
        if (proposal.inconclusive) SetupNotice(stringResource(R.string.setup_repair_inconclusive_warning))
        SetupRelayRepairContent(checkNotNull(proposal.relayRepair), findings)
    } else {
        if (proposal.inconclusive) SetupNotice(stringResource(R.string.setup_repair_inconclusive_warning))
        if (proposal.previousEventId != null) SetupNotice(stringResource(R.string.setup_replacement_warning))
        val listTitle =
            if (proposal.step == OnboardingStepFfi.INBOX_RELAYS) {
                R.string.setup_inbox_list
            } else {
                R.string.setup_read_list
            }
        Text(stringResource(listTitle), style = MaterialTheme.typography.labelLarge)
        SetupNotice(proposal.readRelays.joinToString("\n").ifEmpty { stringResource(R.string.setup_empty_list) })
        if (proposal.step == OnboardingStepFfi.RELAYS) {
            Text(stringResource(R.string.setup_write_list), style = MaterialTheme.typography.labelLarge)
            SetupNotice(proposal.writeRelays.joinToString("\n").ifEmpty { stringResource(R.string.setup_empty_list) })
        }
    }
}

internal val setupEditorActions =
    setOf(
        OnboardingActionFfi.EDIT_PROFILE,
        OnboardingActionFfi.EDIT_RELAYS,
        OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
    )

/** Preview the initial preflight before any network result is available. */
@Preview
@Composable
private fun AccountSetupPreview() {
    WhiteNoiseTheme {
        AccountSetupContent(
            AccountSetupState(busy = true),
            {},
            { _, _, _ -> },
            {},
            {},
            {},
            {},
            {},
            {},
            { "Quiet Otter" },
        )
    }
}
