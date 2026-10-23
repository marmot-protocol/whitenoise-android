@file:Suppress("FunctionNaming") // Compose screen functions use the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
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
    SetupPage(stringResource(R.string.setup_title), back, actions = {}) {
        SetupStageProgress(state)
        SetupErrors(state, onReconnect)
        when {
            state.editor != null -> {
                val editor = state.editor
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
                SetupEditorActions(
                    editor,
                    state.busy,
                    editor.action != OnboardingActionFfi.EDIT_RELAYS ||
                        fields.current(editor).canReviewRelayEdit,
                    onSave = {
                        onEditorChange(fields.current(editor))
                        onSaveEditor()
                    },
                    onDismiss = onDismissEditor,
                )
            }
            snapshot?.ready == true -> {
                SetupProgress(ready = true)
                WhiteNoiseButton(
                    onClick = onOpenChats,
                    enabled = !snapshot.cancellationPending && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("setup-open-chats"),
                ) { Text(stringResource(R.string.setup_open_chats)) }
            }
            state.busy ||
                (
                    !state.error &&
                        !state.disconnected &&
                        !state.staleDecision &&
                        (
                            snapshot == null ||
                                state.optionalMetadataPending ||
                                (
                                    step?.status == dev.ipf.marmotkit.OnboardingStatusFfi.CHECKING &&
                                        step.actions.isEmpty() &&
                                        snapshot.proposal == null
                                )
                        )
                ) -> {
                SetupProgress()
                if (snapshot != null && step != null) SetupOperationNotice(snapshot, step, showCancellation = true)
            }
            else -> {
                if (step != null) {
                    SetupDecisionHeading(state)
                    SetupDecisionContent(state)
                    SetupDirectRelayRemoval(state, onRemoveRelay)
                    if (snapshot?.proposal?.relayRepair != null &&
                        OnboardingActionFfi.APPROVE_REPAIR in step.actions
                    ) {
                        Text(
                            stringResource(R.string.setup_relay_publication_scope),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val blockedByFailure = state.error || state.staleDecision || state.optionalMetadataPending
                    val reconnectOffered = OnboardingActionFfi.RECONNECT_SIGNER in step.actions
                    if (!blockedByFailure && (!state.disconnected || reconnectOffered)) {
                        SetupActionButtons(state, onAction, onEdit)
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

/** Single exact endpoint removal enters native review; all other repair choices remain below. */
@Composable
private fun SetupDirectRelayRemoval(
    state: AccountSetupState,
    onRemoveRelay: (OnboardingStepFfi, String, ULong, String?) -> Unit,
) {
    val snapshot = state.snapshot
    val step = state.currentStep
    val endpoint = state.directRemovalEndpoint()
    if (snapshot == null || step == null || endpoint == null) return
    val revision = snapshot.revision
    val recoveryEpoch = snapshot.recoveryEpoch
    Text(endpoint, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
    WhiteNoiseButton(
        onClick = { onRemoveRelay(step.step, endpoint, revision, recoveryEpoch) },
        enabled = !state.busy && !state.error && !state.disconnected && !state.staleDecision,
        modifier = Modifier.fillMaxWidth().testTag("setup-remove-relay"),
    ) {
        Icon(painterResource(R.drawable.ic_delete), null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.setup_remove_relay))
    }
}

/** Idle checkpoint inspection remains available even when a saved operation offers no mutation yet. */
internal val AccountSetupState.checklistInspectionStep: OnboardingStepFfi?
    get() = currentStep?.takeIf { !busy }?.step

/** Separates connection failure from a decision that became stale while it was on screen. */
@Composable
private fun SetupErrors(
    state: AccountSetupState,
    onReconnect: () -> Unit,
) {
    val connectionLost = state.disconnected && state.snapshot?.ready != true
    if (state.error || connectionLost || state.staleDecision) {
        Text(
            stringResource(if (state.staleDecision) R.string.setup_stale else R.string.setup_retry_help),
            color = MaterialTheme.colorScheme.error,
        )
        if (state.snapshot?.ready != true) {
            OutlinedButton(onClick = onReconnect, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_resume_checks))
            }
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
            SetupSingleDeviceNotice(snapshot.singleDeviceNotice?.discovery)
        }
        snapshot.proposal?.takeIf { it.step == step.step }?.let { proposal ->
            if (OnboardingActionFfi.APPROVE_REPAIR !in step.actions) {
                step.findings.distinct().forEach { SetupFindingContent(it) }
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
    step.findings
        .distinct()
        .filterNot { finding ->
            val routineDeviceNotice = step.step == OnboardingStepFfi.SINGLE_DEVICE && state.routineDeviceNotice
            val directRemovalFinding =
                directRemovalEndpoint != null &&
                    finding.issue == OnboardingIssueFfi.RETIRED_RELAY &&
                    finding.endpoint == directRemovalEndpoint
            routineDeviceNotice || directRemovalFinding
        }.forEach { SetupFindingContent(it) }
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

/** Explains device evidence without treating published packages as online presence. */
@Composable
private fun SetupSingleDeviceNotice(discovered: OnboardingDeviceDiscoveryFfi?) {
    val discovery = discovered ?: OnboardingDeviceDiscoveryFfi.UNKNOWN
    Text(stringResource(R.string.setup_one_phone_per_account_help))
    if (discovery == OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE) {
        SetupNotice(stringResource(R.string.setup_other_device_possible))
    }
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
        SetupRelayRepairContent(checkNotNull(proposal.relayRepair), findings)
    } else {
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

/** Saving still creates a review proposal; the separate native approval remains mandatory. */
@Composable
private fun SetupEditorActions(
    editor: SetupEditor,
    busy: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseButton(
        onClick = onSave,
        enabled = !busy && canSave,
        modifier = Modifier.fillMaxWidth().testTag("setup-editor-save"),
    ) {
        val label =
            if (editor.action == OnboardingActionFfi.EDIT_DISCOVERY_RELAYS) {
                R.string.setup_check_discovery
            } else {
                R.string.setup_review_changes
            }
        Text(stringResource(label))
    }
    TextButton(
        onClick = onDismiss,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.cancel)) }
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
