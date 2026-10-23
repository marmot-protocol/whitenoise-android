@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingIssueFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton

/** Native actions retain exact revision and epoch, with one primary decision per state. */
@Composable
internal fun SetupActionButtons(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
) {
    if (state.snapshot == null) return
    val step = state.currentStep ?: return
    val offered =
        state.orderedRelayActions(step.actions).filterNot { action ->
            val routineRetry = action == OnboardingActionFfi.RETRY && state.routineDeviceNotice
            val disconnectedNonSigner = state.disconnected && action != OnboardingActionFfi.RECONNECT_SIGNER
            routineRetry || disconnectedNonSigner
        }
    val plan = state.actionPlan(offered)
    val visible = listOfNotNull(plan.primary, plan.secondary).distinct()
    visible.forEach { action ->
        SetupOfferedAction(state, action, action == plan.primary && !plan.directRemove, onAction, onEdit)
    }
}

/** Other native grants remain reachable under the shared Details disclosure. */
@Composable
internal fun SetupAlternativeActions(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
) {
    if (!state.hidesAlternativeActions) {
        state.currentStep?.let { step ->
            val offered =
                state.orderedRelayActions(step.actions).filterNot {
                    it == OnboardingActionFfi.RETRY && state.routineDeviceNotice
                }
            val plan = state.actionPlan(offered)
            val visible = listOfNotNull(plan.primary, plan.secondary).distinct()
            val alternatives = offered.filterNot { it in visible }
            if (alternatives.isNotEmpty() || state.offersRelayReset()) {
                Text(stringResource(R.string.setup_other_options), style = MaterialTheme.typography.titleSmall)
                alternatives.forEach { action -> SetupOfferedAction(state, action, false, onAction, onEdit) }
                if (state.offersRelayReset()) SetupResetRelayAction(state, onAction)
            }
        }
    }
}

private val AccountSetupState.hidesAlternativeActions: Boolean
    get() {
        val blockedByEditor = editor != null || error || disconnected
        val blockedByDecision = staleDecision || optionalMetadataPending
        return blockedByEditor || blockedByDecision
    }

/** Full replacement is a separate offered action and captures the rendered native grant. */
@Composable
private fun SetupResetRelayAction(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
) {
    val snapshot = state.snapshot ?: return
    val step = state.currentStep ?: return
    val revision = snapshot.revision
    val recoveryEpoch = snapshot.recoveryEpoch
    TextButton(
        onClick = {
            onAction(
                SetupRequest(
                    revision,
                    step.step,
                    OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
                    recoveryEpoch = recoveryEpoch,
                    resetRelays = true,
                ),
            )
        },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth().testTag("setup-reset-relays"),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(stringResource(R.string.setup_reset_relays)) }
}

/** Pure projection of the current native grant, including the focused exact Remove choice. */
private data class SetupActionPlan(
    val primary: OnboardingActionFfi?,
    val secondary: OnboardingActionFfi?,
    val directRemove: Boolean,
)

private fun AccountSetupState.actionPlan(offered: List<OnboardingActionFfi>): SetupActionPlan {
    val snapshot = checkNotNull(snapshot)
    val step = checkNotNull(currentStep)
    val directRemove = directRemoveAvailable(offered)
    val primary =
        when {
            snapshot.cancellationPending -> offered.firstOrNull()
            OnboardingActionFfi.APPROVE_REPAIR in offered -> OnboardingActionFfi.APPROVE_REPAIR
            step.step == OnboardingStepFfi.PROFILE && OnboardingActionFfi.CONTINUE_WITHOUT in offered ->
                OnboardingActionFfi.CONTINUE_WITHOUT
            step.step == OnboardingStepFfi.SINGLE_DEVICE && OnboardingActionFfi.CONTINUE_ANYWAY in offered ->
                OnboardingActionFfi.CONTINUE_ANYWAY
            OnboardingActionFfi.RECONNECT_SIGNER in offered -> OnboardingActionFfi.RECONNECT_SIGNER
            directRemove -> null
            OnboardingActionFfi.USE_RECOMMENDED_RELAYS in offered -> OnboardingActionFfi.USE_RECOMMENDED_RELAYS
            OnboardingActionFfi.RETRY in offered -> OnboardingActionFfi.RETRY
            else -> offered.firstOrNull()
        }
    val secondary =
        if (directRemove) {
            OnboardingActionFfi.EDIT_RELAYS
        } else {
            offered.firstOrNull {
                it != primary && it != OnboardingActionFfi.CANCEL_ONBOARDING && it != OnboardingActionFfi.RETRY
            }
        }
    return SetupActionPlan(primary, secondary, directRemove)
}

private fun AccountSetupState.directRemoveAvailable(offered: List<OnboardingActionFfi>): Boolean {
    val step = currentStep ?: return false
    return step.step in relaySetupSteps &&
        snapshot?.proposal == null &&
        OnboardingActionFfi.EDIT_RELAYS in offered &&
        step.findings
            .filter { it.issue == OnboardingIssueFfi.RETIRED_RELAY }
            .mapNotNull { it.endpoint?.takeIf(String::isNotBlank) }
            .distinct()
            .size == 1
}

/** Dispatches only currently offered actions and proposal revision for approval. */
@Composable
private fun SetupOfferedAction(
    state: AccountSetupState,
    action: OnboardingActionFfi,
    primary: Boolean,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
) {
    val snapshot = state.snapshot ?: return
    val step = state.currentStep ?: return
    val recoveryEpoch = snapshot.recoveryEpoch
    val revision =
        if (action == OnboardingActionFfi.APPROVE_REPAIR) {
            snapshot.proposal?.takeIf { it.step == step.step }?.revision ?: snapshot.revision
        } else {
            snapshot.revision
        }
    val canApprove =
        action != OnboardingActionFfi.APPROVE_REPAIR ||
            snapshot.proposal?.step == step.step
    SetupActionButton(
        action,
        primary,
        !state.busy && canApprove,
        if (action == OnboardingActionFfi.CANCEL_REPAIR && state.removalReview) {
            R.string.setup_undo_removal
        } else {
            setupActionTitle(action)
        },
    ) {
        if (action in setupEditorActions) {
            onEdit(step.step, action, revision)
        } else {
            onAction(SetupRequest(revision, step.step, action, recoveryEpoch = recoveryEpoch))
        }
    }
}

/** Uses one filled action, quiet alternatives, and a distinct cancellation treatment. */
@Composable
private fun SetupActionButton(
    action: OnboardingActionFfi,
    primary: Boolean,
    enabled: Boolean,
    labelId: Int,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().testTag("setup-action-${action.name}")
    val label = stringResource(labelId)
    val content: @Composable () -> Unit = {
        Icon(painterResource(setupActionIcon(action)), null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    when {
        action == OnboardingActionFfi.CANCEL_ONBOARDING ->
            TextButton(
                onClick,
                modifier,
                enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { content() }
        primary -> WhiteNoiseButton(onClick, modifier, enabled) { content() }
        action == OnboardingActionFfi.RETRY || action == OnboardingActionFfi.CANCEL_REPAIR ->
            TextButton(onClick, modifier, enabled) { content() }
        else -> OutlinedButton(onClick, modifier, enabled) { content() }
    }
}

/**
 * Orders native decisions supported by v1. Follows are checked or skipped without publishing a replacement
 * (issue #2519); EDIT_FOLLOWS therefore intentionally has no editor or publication grant in this flow.
 */
internal fun setupOrderedActions(actions: List<OnboardingActionFfi>) = setupActionOrder.filter { it in actions }

private val setupActionOrder =
    listOf(
        OnboardingActionFfi.APPROVE_REPAIR,
        OnboardingActionFfi.CONTINUE_ANYWAY,
        OnboardingActionFfi.EDIT_PROFILE,
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS,
        OnboardingActionFfi.RECONNECT_SIGNER,
        OnboardingActionFfi.CONTINUE_WITHOUT,
        OnboardingActionFfi.RETRY,
        OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
        OnboardingActionFfi.EDIT_RELAYS,
        OnboardingActionFfi.CANCEL_REPAIR,
        OnboardingActionFfi.CANCEL_ONBOARDING,
    )

/** Keeps manual review beside minimal repair without changing the native offered action set. */
private fun AccountSetupState.orderedRelayActions(actions: List<OnboardingActionFfi>): List<OnboardingActionFfi> {
    val ordered = setupOrderedActions(actions)
    return if (currentStep?.step in relaySetupSteps && snapshot?.proposal == null) {
        listOf(OnboardingActionFfi.USE_RECOMMENDED_RELAYS, OnboardingActionFfi.EDIT_RELAYS)
            .filter { it in ordered } + ordered.filterNot { it in relaySetupActions }
    } else {
        ordered
    }
}

/** Reset is visible only among expanded native repair choices, before a proposal exists. */
private fun AccountSetupState.offersRelayReset(): Boolean {
    if (snapshot?.proposal != null) return false
    val step = currentStep
    return step != null && step.step in relaySetupSteps && OnboardingActionFfi.USE_RECOMMENDED_RELAYS in step.actions
}

/** Icons describe the offered operation; a decision to skip or continue never displays a success check. */
private fun setupActionIcon(action: OnboardingActionFfi): Int =
    when (action) {
        OnboardingActionFfi.RETRY, OnboardingActionFfi.RECONNECT_SIGNER -> R.drawable.ic_refresh
        OnboardingActionFfi.EDIT_RELAYS, OnboardingActionFfi.EDIT_DISCOVERY_RELAYS,
        OnboardingActionFfi.EDIT_PROFILE, OnboardingActionFfi.EDIT_FOLLOWS,
        -> R.drawable.ic_edit
        OnboardingActionFfi.CANCEL_REPAIR, OnboardingActionFfi.CANCEL_ONBOARDING -> R.drawable.ic_close
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS -> R.drawable.ic_refresh
        OnboardingActionFfi.CONTINUE_WITHOUT, OnboardingActionFfi.CONTINUE_ANYWAY -> R.drawable.ic_forward
        OnboardingActionFfi.APPROVE_REPAIR -> R.drawable.ic_check
    }
