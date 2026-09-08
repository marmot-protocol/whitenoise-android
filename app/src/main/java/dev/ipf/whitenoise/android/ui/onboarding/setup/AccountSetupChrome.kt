@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Compact account context and progress leave the current decision near the top of a phone screen. */
@Composable
internal fun SetupHeader(state: AccountSetupState) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.snapshot?.let { snapshot ->
            LinearProgressIndicator(
                progress = { state.completedSteps.toFloat() / snapshot.steps.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A readable status history remains available below the decision, including optional skipped checks. */
@Composable
internal fun SetupChecklist(snapshot: OnboardingSnapshotFfi) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
        snapshot.steps.forEach { step ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                verticalAlignment = Alignment.Top,
            ) {
                SetupStatusIcon(step.status)
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    Text(stringResource(setupStepTitle(step.step)), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(setupStatusTitle(step.status)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Text always accompanies these decorative icons so status does not depend on color alone. */
@Composable
internal fun SetupStatusIcon(status: OnboardingStatusFfi) {
    val modifier = Modifier.size(24.dp)
    if (status == OnboardingStatusFfi.CHECKING) {
        CircularProgressIndicator(modifier, strokeWidth = 2.dp)
    } else {
        val icon =
            when (status) {
                OnboardingStatusFfi.PASSED -> Icons.Outlined.CheckCircle
                OnboardingStatusFfi.SKIPPED -> Icons.Outlined.RemoveCircleOutline
                OnboardingStatusFfi.PENDING -> Icons.Outlined.RadioButtonUnchecked
                else -> Icons.Outlined.ErrorOutline
            }
        Icon(
            icon,
            contentDescription = null,
            modifier = modifier,
            tint =
                when (status) {
                    OnboardingStatusFfi.PASSED -> MaterialTheme.colorScheme.primary
                    OnboardingStatusFfi.RETRYABLE_FAILURE -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/** Separates publication consequences and recovery guidance from the surrounding form fields. */
@Composable
internal fun SetupNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(Dimens.spaceMd),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
