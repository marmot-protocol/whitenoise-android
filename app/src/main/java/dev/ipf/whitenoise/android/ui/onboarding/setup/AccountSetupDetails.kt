@file:Suppress("FunctionNaming") // Compose UI functions follow the framework naming convention.

package dev.ipf.whitenoise.android.ui.onboarding.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingFindingFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Quiet default state while native checks and non-destructive defaults advance. */
@Composable
internal fun SetupProgress(ready: Boolean = false) {
    Column(
        Modifier.padding(vertical = Dimens.spaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
        Text(
            stringResource(if (ready) R.string.setup_opening_chats else R.string.setup_working),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
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
            Text(state.accountLabel, style = MaterialTheme.typography.labelMedium)
            state.currentStep?.findings?.forEach { finding ->
                SetupFindingContent(finding)
            }
            SetupChecklist(snapshot)
        }
        Text(
            stringResource(R.string.setup_later_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Routine device consent keeps only Details; work and recovery retain an explicit save-and-exit action. */
@Composable
internal fun SetupFooter(
    state: AccountSetupState,
    onToggleDetails: () -> Unit,
    onLater: () -> Unit,
) {
    if (state.snapshot?.ready == true) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (!state.busy && state.snapshot != null) {
                TextButton(onClick = onToggleDetails, modifier = Modifier.testTag("setup-details")) {
                    Text(
                        stringResource(
                            if (state.detailsExpanded) R.string.setup_hide_details else R.string.setup_details,
                        ),
                    )
                }
            }
            if (!state.routineDeviceNotice) {
                TextButton(onClick = onLater, modifier = Modifier.testTag("setup-later")) {
                    Text(stringResource(R.string.setup_later))
                }
            }
        }
        if (state.detailsExpanded && !state.busy) SetupDetails(state)
    }
}

/** Keeps the affected endpoint beside its finding, including before the user opens diagnostic details. */
@Composable
internal fun SetupFindingContent(finding: OnboardingFindingFfi) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        finding.endpoint?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(setupFindingTitle(finding.issue)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
