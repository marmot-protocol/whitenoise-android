@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingDeviceDiscoveryFfi
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingRelayRepairModeFfi
import dev.ipf.marmotkit.OnboardingRepairProposalFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.outlined.AccountCircle
import dev.ipf.whitenoise.android.ui.icons.outlined.Security
import dev.ipf.whitenoise.android.ui.icons.outlined.Smartphone
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Titles are tied to the current native state, never to an invented setup policy. */
@Composable
internal fun SetupDecisionHeading(state: AccountSetupState) {
    val step = state.currentStep ?: return
    val proposal = state.snapshot?.proposal?.takeIf { it.step == step.step }
    val title = setupPendingHeadingTitle(state, step, proposal) ?: setupDefaultHeadingTitle(step, proposal)
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
}

private fun setupPendingHeadingTitle(
    state: AccountSetupState,
    step: OnboardingStepStateFfi,
    proposal: OnboardingRepairProposalFfi?,
): Int? =
    when {
        state.snapshot?.cancellationPending == true -> R.string.setup_cancel_pending_title
        proposal != null &&
            proposal.relayRepair?.mode != OnboardingRelayRepairModeFfi.MANUAL_REVIEW &&
            OnboardingActionFfi.APPROVE_REPAIR !in step.actions -> R.string.setup_approved_pending_title
        step.status == dev.ipf.marmotkit.OnboardingStatusFfi.WAITING_FOR_SIGNER -> R.string.setup_signer_title
        proposal?.profile != null && OnboardingActionFfi.APPROVE_REPAIR in step.actions ->
            R.string.setup_profile_review_title
        proposal?.relayRepair != null && OnboardingActionFfi.APPROVE_REPAIR in step.actions ->
            R.string.setup_relay_review_title
        else -> null
    }

private fun setupDefaultHeadingTitle(
    step: OnboardingStepStateFfi,
    proposal: OnboardingRepairProposalFfi?,
): Int =
    when {
        step.step == OnboardingStepFfi.PROFILE &&
            proposal == null &&
            OnboardingActionFfi.CONTINUE_WITHOUT in step.actions -> R.string.setup_profile_optional_title
        step.findings.any { it.issue == OnboardingIssueFfi.RETIRED_RELAY } -> R.string.setup_relay_retired_title
        step.step == OnboardingStepFfi.SINGLE_DEVICE -> R.string.setup_one_phone_per_account
        else -> setupStepTitle(step.step)
    }

/** A snapshot-only stage count with TalkBack language in stages rather than percentages. */
@Composable
internal fun SetupStageProgress(state: AccountSetupState) {
    val steps = state.snapshot?.steps.orEmpty()
    val total = steps.size
    val current = state.currentStep
    val completed = state.completedSteps
    val currentIndex = current?.let { selected -> steps.indexOfFirst { it.step == selected.step } + 1 } ?: 0
    val completedLabel = if (total > 0) stringResource(R.string.setup_stage_completed, completed, total) else null
    val stageLabel =
        if (currentIndex > 0 && current != null) {
            stringResource(
                R.string.setup_stage_label,
                currentIndex,
                total,
                stringResource(setupStepTitle(current.step)),
            )
        } else {
            null
        }
    val statusLabel = current?.let { stringResource(setupStatusTitle(it.status)) }
    val stopped = state.error || state.disconnected || state.staleDecision
    val description =
        if (total > 0) {
            listOfNotNull(completedLabel, stageLabel, statusLabel).joinToString(". ")
        } else {
            stringResource(if (stopped) R.string.setup_stage_unavailable else R.string.setup_working)
        }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        SetupStageProgressBar(total, completed, stopped, description)
        stageLabel?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clearAndSetSemantics {})
        }
        completedLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun SetupStageProgressBar(
    total: Int,
    completed: Int,
    stopped: Boolean,
    description: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .testTag("setup-stage-progress")
            .clearAndSetSemantics {
                stateDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f)
        if (total > 0 || stopped) {
            LinearProgressIndicator(
                progress = { if (total > 0) completed.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
                trackColor = track,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), trackColor = track)
        }
    }
}

/** Quiet default state while native checks and non-destructive defaults advance. */
@Composable
internal fun SetupProgress(ready: Boolean = false) {
    Column(
        Modifier.padding(vertical = Dimens.spaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(
            stringResource(if (ready) R.string.setup_opening_chats else R.string.setup_working),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (!ready) {
            Text(
                stringResource(R.string.setup_stage_time_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A single decorative anchor distinguishes profile, device, and connection decisions. */
@Composable
internal fun SetupStepIcon(step: OnboardingStepFfi) {
    Icon(
        when (step) {
            OnboardingStepFfi.PROFILE -> Icons.Outlined.AccountCircle
            OnboardingStepFfi.SINGLE_DEVICE -> Icons.Outlined.Smartphone
            else -> Icons.Outlined.Security
        },
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Diagnostic history is available on demand rather than repeated above every decision. */
@Composable
internal fun SetupDetails(state: AccountSetupState) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        state.snapshot?.let { snapshot ->
            snapshot.proposal?.takeIf { it.step == state.currentStep?.step }?.relayRepair?.let { repair ->
                SetupRelayRepairDetails(repair, state.currentStep?.findings.orEmpty())
            }
            Text(stringResource(R.string.setup_checks), style = MaterialTheme.typography.titleMedium)
            Text(state.accountLabel, style = MaterialTheme.typography.labelMedium)
            state.currentStep?.findings?.forEach { finding ->
                SetupFindingContent(finding)
            }
            if (state.currentStep?.step == OnboardingStepFfi.SINGLE_DEVICE) {
                Text(
                    stringResource(
                        when (snapshot.singleDeviceNotice?.discovery ?: OnboardingDeviceDiscoveryFfi.UNKNOWN) {
                            OnboardingDeviceDiscoveryFfi.NONE_FOUND -> R.string.setup_device_discovery_none
                            OnboardingDeviceDiscoveryFfi.OTHER_INSTALLATION_POSSIBLE ->
                                R.string.setup_other_device_possible
                            OnboardingDeviceDiscoveryFfi.UNKNOWN -> R.string.setup_device_discovery_unknown
                        },
                    ),
                )
            }
            SetupChecklist(snapshot)
        }
    }
}

/** Keeps the affected endpoint beside its finding, including before the user opens diagnostic details. */
@Composable
internal fun SetupFindingContent(finding: OnboardingFindingFfi) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        Icon(painterResource(R.drawable.ic_info), null, Modifier.size(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            finding.endpoint?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            textDirection = androidx.compose.ui.text.style.TextDirection.Ltr,
                        ),
                )
            }
            Text(
                stringResource(setupFindingTitle(finding.issue)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Current checkpoint is immediately inspectable without a full checklist. */
@Composable
internal fun SetupCurrentStep(
    step: OnboardingStepStateFfi,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("setup-step-${step.step.name}")) {
        SetupStatusIcon(step.status)
        Column(Modifier.weight(1f).padding(start = Dimens.spaceSm)) {
            Text(stringResource(setupStepTitle(step.step)), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(setupStatusTitle(step.status)), style = MaterialTheme.typography.bodySmall)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null)
    }
}
