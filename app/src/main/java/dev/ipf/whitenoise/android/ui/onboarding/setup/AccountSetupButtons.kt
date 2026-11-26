@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton

/** Native actions retain exact revision and epoch, with one primary decision per state. */
@Composable
internal fun SetupActionButtons(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
) {
    state.decisionButtons().forEach { (action, primary) ->
        SetupOfferedAction(state, action, primary, onAction, onEdit)
    }
}

/** The native plan's visible decision buttons, each paired with whether it is the one filled primary. */
internal fun AccountSetupState.decisionButtons(): List<Pair<OnboardingActionFfi, Boolean>> {
    val step = currentStep
    if (snapshot == null || step == null) return emptyList()
    val offered =
        orderedRelayActions(step.actions).filterNot { action ->
            val routineRetry = action == OnboardingActionFfi.RETRY && routineDeviceNotice
            val disconnectedNonSigner = disconnected && action != OnboardingActionFfi.RECONNECT_SIGNER
            routineRetry || disconnectedNonSigner
        }
    val plan = actionPlan(offered)
    return listOfNotNull(plan.primary, plan.secondary).distinct().map { action ->
        action to (action == plan.primary && !plan.directRemove)
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
    val enabled = !state.busy
    WhiteNoiseOutlinedButton(
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
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("setup-reset-relays"),
    ) { SetupDestructiveContent(enabled) { Text(stringResource(R.string.setup_reset_relays)) } }
}

/** Dispatches only currently offered actions and proposal revision for approval. */
@Suppress("LongParameterList") // A passed step uses its own native grant.
@Composable
internal fun SetupOfferedAction(
    state: AccountSetupState,
    action: OnboardingActionFfi,
    primary: Boolean,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
    step: OnboardingStepFfi? = state.currentStep?.step,
) {
    val snapshot = state.snapshot ?: return
    if (step == null) return
    val recoveryEpoch = snapshot.recoveryEpoch
    val revision =
        if (action == OnboardingActionFfi.APPROVE_REPAIR) {
            snapshot.proposal?.takeIf { it.step == step }?.revision ?: snapshot.revision
        } else {
            snapshot.revision
        }
    val canApprove =
        action != OnboardingActionFfi.APPROVE_REPAIR ||
            snapshot.proposal?.step == step
    val enabled = !state.busy && canApprove
    val labelId =
        if (action == OnboardingActionFfi.CANCEL_REPAIR && state.removalReview) {
            R.string.setup_undo_removal
        } else {
            setupActionTitle(action)
        }
    val onClick = {
        if (action in setupEditorActions) {
            onEdit(step, action, revision)
        } else {
            onAction(SetupRequest(revision, step, action, recoveryEpoch = recoveryEpoch))
        }
    }
    val modifier = Modifier.fillMaxWidth().testTag("setup-action-${action.name}")
    val content: @Composable () -> Unit = {
        Icon(painterResource(setupActionIcon(action)), null)
        SetupButtonIconGap()
        Text(stringResource(labelId))
    }
    // One filled action per decision; alternatives and cancellation use the shared outlined button.
    when {
        action == OnboardingActionFfi.CANCEL_ONBOARDING ->
            WhiteNoiseOutlinedButton(onClick, modifier, enabled) { SetupDestructiveContent(enabled, content) }
        primary -> WhiteNoiseButton(onClick, modifier, enabled) { content() }
        else -> WhiteNoiseOutlinedButton(onClick, modifier, enabled) { content() }
    }
}

/** Colours an outlined button's content as destructive while it is enabled; disabled keeps Material's treatment. */
@Composable
private fun SetupDestructiveContent(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error, content = content)
    } else {
        content()
    }
}

/** The gap between a setup button's leading icon and its label. */
@Composable
internal fun SetupButtonIconGap() {
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
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
        OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS,
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
        OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS,
        OnboardingActionFfi.EDIT_PROFILE, OnboardingActionFfi.EDIT_FOLLOWS,
        -> R.drawable.ic_edit
        OnboardingActionFfi.CANCEL_REPAIR, OnboardingActionFfi.CANCEL_ONBOARDING -> R.drawable.ic_close
        OnboardingActionFfi.USE_RECOMMENDED_RELAYS -> R.drawable.ic_settings_backup_restore
        OnboardingActionFfi.CONTINUE_WITHOUT, OnboardingActionFfi.CONTINUE_ANYWAY -> R.drawable.ic_forward
        OnboardingActionFfi.APPROVE_REPAIR -> R.drawable.ic_check
    }
