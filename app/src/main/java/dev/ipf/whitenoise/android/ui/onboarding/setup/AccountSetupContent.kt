@file:Suppress("FunctionNaming") // Compose screen functions use the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Lifecycle-aware route; the process-owned controller survives Activity recreation and signer handoff. */
@Composable
internal fun AccountSetupScreen(
    controller: AccountSetupController,
    onLater: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onLater)
    AccountSetupContent(
        state,
        controller::submit,
        controller::edit,
        controller::updateEditor,
        { state.editor?.let { controller.submit(it.request()) } },
        controller::dismissEditor,
        controller::reconnect,
        controller::openChats,
        onLater,
        controller::toggleDetails,
    )
}

/** Scrollable checklist and decisions, usable with large fonts, narrow windows, RTL, and the keyboard. */
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Dimens.spaceXl),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
            ) {
                SetupHeader(state)
                SetupErrors(state, onReconnect)
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
                ) {
                    when {
                        state.editor != null ->
                            SetupEditorContent(state.editor, state.busy, onEditorChange, onSaveEditor, onDismissEditor)
                        state.busy || state.snapshot == null -> SetupProgress(state.snapshot?.ready == true)
                        state.optionalMetadataPending -> {
                            if (!state.error && !state.disconnected && !state.staleDecision) SetupProgress()
                        }
                        else -> SetupDecisionContent(state, onAction, onEdit, onOpenChats)
                    }
                }
                SetupFooter(state, onToggleDetails, onLater)
            }
        }
    }
}

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
private fun SetupDecisionContent(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
    onOpenChats: () -> Unit,
) {
    val snapshot = state.snapshot
    val step = state.currentStep
    if (snapshot == null) return
    if (snapshot.ready && !snapshot.cancellationPending) {
        Text(stringResource(R.string.setup_ready), style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = onOpenChats,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("setup-open-chats"),
        ) {
            Text(stringResource(R.string.setup_open_chats))
        }
    } else if (step != null) {
        SetupStepIcon(step.step)
        Text(stringResource(setupStepTitle(step.step)), style = MaterialTheme.typography.headlineSmall)
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
        SetupActionButtons(state, onAction, onEdit)
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

/** Keeps failed drafts visible and turns Save into a proposal, never implicit publication. */
@Composable
private fun SetupEditorContent(
    editor: SetupEditor,
    busy: Boolean,
    onChange: (SetupEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (editor.action == OnboardingActionFfi.EDIT_PROFILE) {
        SetupProfileFields(editor, busy, onChange)
    } else {
        val help =
            if (editor.action == OnboardingActionFfi.EDIT_DISCOVERY_RELAYS) {
                R.string.setup_discovery_help
            } else {
                R.string.setup_relays_help
            }
        Text(stringResource(help))
        OutlinedTextField(
            editor.reads,
            { onChange(editor.copy(reads = it)) },
            label = {
                Text(
                    stringResource(
                        when {
                            editor.action == OnboardingActionFfi.EDIT_DISCOVERY_RELAYS ->
                                R.string.setup_discovery_list
                            editor.step == OnboardingStepFfi.INBOX_RELAYS -> R.string.setup_inbox_list
                            else -> R.string.setup_read_list
                        },
                    ),
                )
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (editor.action == OnboardingActionFfi.EDIT_RELAYS && editor.step == OnboardingStepFfi.RELAYS) {
            OutlinedTextField(
                editor.writes,
                { onChange(editor.copy(writes = it)) },
                label = { Text(stringResource(R.string.setup_write_list)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
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

/** Edits only the two selected profile fields, retaining all other native metadata. */
@Composable
private fun SetupProfileFields(
    editor: SetupEditor,
    busy: Boolean,
    onChange: (SetupEditor) -> Unit,
) {
    OutlinedTextField(
        editor.displayName,
        { onChange(editor.copy(displayName = it)) },
        label = { Text(stringResource(R.string.setup_display_name)) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        editor.about,
        { onChange(editor.copy(about = it)) },
        label = { Text(stringResource(R.string.setup_about)) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
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
