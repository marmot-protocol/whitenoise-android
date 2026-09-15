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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingStatusFfi
import dev.ipf.marmotkit.OnboardingStepFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.theme.Dimens

/** Native checkpoint history; only the current checkpoint is inspectable, never inferred completion. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupChecklist(
    snapshot: OnboardingSnapshotFfi,
    actionableStep: OnboardingStepFfi? = null,
    onStep: (OnboardingStepFfi) -> Unit = {},
) {
    Column {
        snapshot.steps.forEach { step ->
            val status = stringResource(setupStatusTitle(step.status))
            val modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription = status
                        liveRegion = LiveRegionMode.Polite
                    }.testTag("setup-step-${step.step.name}")
            val leading: @Composable () -> Unit = { SetupStatusIcon(step.status) }
            val supporting: @Composable () -> Unit = { Text(status, Modifier.clearAndSetSemantics {}) }
            val headline: @Composable () -> Unit = { Text(stringResource(setupStepTitle(step.step))) }
            val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
            if (step.step == actionableStep) {
                ListItem(
                    onClick = { onStep(step.step) },
                    modifier = modifier.semantics { role = Role.Button },
                    shapes = WhiteNoiseListItemDefaults.shapes(),
                    leadingContent = leading,
                    supportingContent = supporting,
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), null) },
                    colors = colors,
                    content = headline,
                )
            } else {
                ListItem(
                    modifier = modifier,
                    leadingContent = leading,
                    supportingContent = supporting,
                    colors = colors,
                    content = headline,
                )
            }
        }
    }
}

/** Visible labels accompany the prototype status artwork; icons never duplicate the spoken status. */
@Composable
internal fun SetupStatusIcon(status: OnboardingStatusFfi) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val success = if (dark) Color(0xFF81C995) else Color(0xFF146C2E)
    val warning = if (dark) Color(0xFFFFB86B) else Color(0xFF934B00)
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(Dimens.spaceMd),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
