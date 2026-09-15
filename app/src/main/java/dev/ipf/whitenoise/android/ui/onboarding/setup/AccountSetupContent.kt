@file:Suppress("FunctionNaming") // Compose screen functions use the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Lifecycle-aware route; the process-owned controller survives Activity recreation and signer handoff. */
@Composable
internal fun AccountSetupScreen(
    controller: AccountSetupController,
    onLater: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    AccountSetupContent(
        state,
        controller::submit,
        controller::edit,
        controller::updateEditor,
        {
            controller.state.value.editor
                ?.let { controller.submit(it.request()) }
        },
        controller::dismissEditor,
        controller::reconnect,
        controller::openChats,
        onLater,
        controller::toggleDetails,
    )
}

/** Checklist and one native decision; navigation changes presentation without acknowledging a checkpoint. */
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
    onToggleDetails: () -> Unit = {},
) {
    val currentStep = state.currentStep?.step
    var detailStep by remember(state.snapshot?.accountIdHex, currentStep) { mutableStateOf<OnboardingStepFfi?>(null) }
    val editorFields = state.editor?.let { rememberSetupEditorFields(state.snapshot?.accountIdHex, it, onEditorChange) }
    val detail = state.editor != null || (detailStep != null && detailStep == currentStep)
    val back: () -> Unit = {
        when {
            state.busy -> onLater()
            state.editor != null -> onDismissEditor()
            detail -> detailStep = null
            else -> onLater()
        }
    }
    BackHandler(onBack = back)
    SetupPage(
        title =
            stringResource(
                if (detail && currentStep != null) setupStepTitle(currentStep) else R.string.setup_title,
            ),
        onBack = back,
        actions = {
            when {
                state.editor != null ->
                    SetupEditorActions(
                        state.editor,
                        state.busy,
                        onSave = {
                            onEditorChange(checkNotNull(editorFields).current(state.editor))
                            onSaveEditor()
                        },
                        onDismiss = onDismissEditor,
                    )
                !detail ->
                    WhiteNoiseButton(
                        onClick = onOpenChats,
                        enabled = state.snapshot?.let { it.ready && !it.cancellationPending } == true && !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("setup-open-chats"),
                    ) { Text(stringResource(R.string.setup_open_chats)) }
                !state.busy && !state.optionalMetadataPending -> SetupActionButtons(state, onAction, onEdit)
            }
        },
    ) {
        SetupErrors(state, onReconnect)
        if (!detail) {
            if (state.snapshot?.ready != true) {
                Text(stringResource(R.string.setup_working), style = MaterialTheme.typography.bodyLarge)
            }
            state.snapshot?.let { snapshot ->
                state.currentStep?.let { SetupOperationNotice(snapshot, it) }
                SetupChecklist(snapshot, state.checklistInspectionStep) { step -> detailStep = step }
            } ?: SetupProgress()
            if (!state.routineDeviceNotice && state.snapshot?.ready != true) {
                TextButton(onClick = onLater, modifier = Modifier.testTag("setup-later")) {
                    Text(stringResource(R.string.setup_later))
                }
            }
        } else {
            when {
                state.editor != null -> SetupEditorContent(state.editor, checkNotNull(editorFields), state.busy)
                state.busy || state.snapshot == null -> SetupProgress(state.snapshot?.ready == true)
                state.optionalMetadataPending -> {
                    if (!state.error && !state.disconnected && !state.staleDecision) SetupProgress()
                }
                else -> SetupDecisionContent(state)
            }
            SetupFooter(state, onToggleDetails, onLater)
        }
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
    val snapshot = state.snapshot
    val step = state.currentStep
    if (snapshot == null) return
    if (step != null) {
        if (step.step == OnboardingStepFfi.PROFILE && snapshot.proposal?.step != step.step) {
            Text(
                stringResource(R.string.setup_profile_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SetupOperationNotice(snapshot, step)
        if (step.step != OnboardingStepFfi.PROFILE && snapshot.proposal?.step != step.step) {
            state.decisionFinding?.let { SetupFindingContent(it) }
        }
        if (step.step == OnboardingStepFfi.SINGLE_DEVICE) {
            SetupSingleDeviceNotice(snapshot.singleDeviceNotice?.discovery)
        }
        snapshot.proposal?.takeIf { it.step == step.step }?.let { SetupProposalContent(it) }
    }
}

/** Distinguishes a saved cancellation from an approved publication that must finish before cancellation. */
@Composable
private fun SetupOperationNotice(
    snapshot: OnboardingSnapshotFfi,
    step: OnboardingStepStateFfi,
) {
    if (snapshot.cancellationPending) {
        SetupNotice(stringResource(R.string.setup_cancel_pending))
    } else if (snapshot.proposal?.step == step.step && OnboardingActionFfi.APPROVE_REPAIR !in step.actions) {
        SetupNotice(stringResource(R.string.setup_approved_repair_pending))
    }
}

/** Explains device evidence without treating published packages as online presence. */
@Composable
private fun SetupSingleDeviceNotice(discovered: OnboardingDeviceDiscoveryFfi?) {
    val discovery = discovered ?: OnboardingDeviceDiscoveryFfi.UNKNOWN
    Text(stringResource(R.string.setup_single_device_help))
    if (discovery == OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE) {
        SetupNotice(stringResource(R.string.setup_device_possible))
    }
}

/** Shows the complete replacement before publication, including separate read/write capabilities. */
@Composable
private fun SetupProposalContent(proposal: OnboardingRepairProposalFfi) {
    if (proposal.step == OnboardingStepFfi.PROFILE) {
        Text(stringResource(R.string.setup_profile_publish_help))
        proposal.profile?.let { profile ->
            Text(profile.displayName.orEmpty())
            Text(profile.about.orEmpty())
        }
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
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    WhiteNoiseButton(
        onClick = onSave,
        enabled = !busy,
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
    WhiteNoiseTheme { AccountSetupContent(AccountSetupState(busy = true), {}, { _, _, _ -> }, {}, {}, {}, {}, {}, {}) }
}
