@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.statusColors

/** Native checkpoint history with optional cleanup alongside warnings on a passed relay step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupChecklist(
    state: AccountSetupState,
    onAction: (SetupRequest) -> Unit,
    onEdit: (OnboardingStepFfi, OnboardingActionFfi, ULong) -> Unit,
) {
    val snapshot = state.snapshot ?: return
    val offerCleanup =
        state.editor == null &&
            !state.showsSetupFailure &&
            !state.busy &&
            !snapshot.cancellationPending &&
            snapshot.proposal == null
    Column {
        snapshot.steps.forEach { step ->
            val status = stringResource(setupStatusTitle(step.status))
            ListItem(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {
                            stateDescription = status
                            liveRegion = LiveRegionMode.Polite
                        }.testTag("setup-step-${step.step.name}"),
                leadingContent = { SetupStatusIcon(step.status) },
                supportingContent = { Text(status, Modifier.clearAndSetSemantics {}) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                content = { Text(stringResource(setupStepTitle(step.step))) },
            )
            val cleanup = OnboardingActionFfi.REMOVE_UNUSABLE_RELAYS
            val canRemove =
                offerCleanup && step.status == OnboardingStatusFfi.PASSED && cleanup in step.actions
            SetupPassedWarnings(step.passedWarnings(), canRemove) {
                SetupOfferedAction(state, cleanup, false, onAction, onEdit, step = step.step)
            }
        }
    }
}

/** Visible labels accompany the prototype status artwork; icons never duplicate the spoken status. */
@Composable
internal fun SetupStatusIcon(status: OnboardingStatusFfi) {
    val success = MaterialTheme.statusColors.success
    val warning = MaterialTheme.statusColors.warning
    Box(Modifier.size(24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        when (status) {
            OnboardingStatusFfi.CHECKING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            OnboardingStatusFfi.PASSED -> Icon(painterResource(R.drawable.ic_check), null, tint = success)
            OnboardingStatusFfi.SKIPPED -> Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OnboardingStatusFfi.PENDING -> Text("○", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Icon(painterResource(R.drawable.ic_warning), null, tint = warning)
        }
    }
}

/** Separates publication consequences and recovery guidance from the surrounding form fields. */
@Composable
internal fun SetupNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(Dimens.spaceMd),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
