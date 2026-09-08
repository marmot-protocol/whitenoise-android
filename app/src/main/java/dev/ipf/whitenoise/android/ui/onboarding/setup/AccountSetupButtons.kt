@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.OnboardingActionFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.marmotkit.OnboardingStepStateFfi

/** Orders presentation only: every offered action retains its exact native revision and callback. */
@Composable
internal fun SetupActionButtons(
    snapshot: OnboardingSnapshotFfi,
    step: OnboardingStepStateFfi,
    busy: Boolean,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
    expanded: Boolean = false,
) {
    val ordered = setupOrderedActions(step.actions)
    val actions =
        if (expanded || snapshot.cancellationPending) {
            ordered
        } else {
            ordered.filter { it != OnboardingActionFfi.CANCEL_ONBOARDING }.take(2)
        }
    actions.forEach { action ->
        val revision =
            if (action == OnboardingActionFfi.APPROVE_REPAIR) {
                snapshot.proposal?.takeIf { it.step == step.step }?.revision ?: snapshot.revision
            } else {
                snapshot.revision
            }
        val canApprove = action != OnboardingActionFfi.APPROVE_REPAIR || snapshot.proposal?.step == step.step
        SetupActionButton(action, primary = action == actions.firstOrNull(), enabled = !busy && canApprove) {
            if (action in setupEditorActions) {
                onEdit(step.step, action, revision)
            } else {
                onAction(SetupRequest(revision, step.step, action))
            }
        }
    }
}

/** Uses one filled action, quiet alternatives, and a distinct cancellation treatment. */
@Composable
private fun SetupActionButton(
    action: OnboardingActionFfi,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().testTag("setup-action-${action.name}")
    val label = stringResource(setupActionTitle(action))
    when {
        action == OnboardingActionFfi.CANCEL_ONBOARDING ->
            TextButton(
                onClick,
                modifier,
                enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(label) }
        primary -> Button(onClick, modifier, enabled) { Text(label) }
        action == OnboardingActionFfi.RETRY || action == OnboardingActionFfi.CANCEL_REPAIR ->
            TextButton(onClick, modifier, enabled) { Text(label) }
        else -> OutlinedButton(onClick, modifier, enabled) { Text(label) }
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
