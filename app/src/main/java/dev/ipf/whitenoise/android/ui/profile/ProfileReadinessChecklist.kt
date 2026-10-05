package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Keep saved-profile status compact while allowing inspection of all six published fields. */
@Composable
@Suppress("FunctionNaming")
internal fun ProfileReadinessChecklist(readiness: ProfileReadiness) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasFields = readiness.fields.isNotEmpty()
    val summary =
        when (readiness) {
            ProfileReadiness.Unavailable -> R.string.profile_readiness_editor_unavailable
            else -> readiness.summary
        }
    val state =
        stringResource(if (expanded) R.string.onboarding_details_expanded else R.string.onboarding_details_collapsed)
    val action = stringResource(if (expanded) R.string.setup_hide_details else R.string.setup_details)
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("profile.readiness"),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = amoledOutlineBorder(),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (hasFields) {
                                Modifier
                                    .clickable(role = Role.Button, onClickLabel = action) { expanded = !expanded }
                                    .semantics { stateDescription = state }
                            } else {
                                Modifier
                            },
                        ).testTag("profile.readiness.toggle")
                        .heightIn(min = 56.dp)
                        .padding(WhiteNoiseSpacing.CompactScreenMargin),
                horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(summary),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (hasFields) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (expanded && hasFields) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileReadinessFields(readiness)
            }
        }
    }
}

/** Field labels preserve optionality and wrap as one TalkBack item without assigning a score. */
@Composable
@Suppress("FunctionNaming")
private fun ProfileReadinessFields(readiness: ProfileReadiness) {
    val labels =
        listOf(
            R.string.profile_readiness_name,
            R.string.profile_readiness_picture,
            R.string.profile_readiness_bio,
            R.string.profile_readiness_banner,
            R.string.profile_readiness_address,
            R.string.profile_readiness_lightning,
        )
    Column(
        Modifier.padding(WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        readiness.fields.forEachIndexed { index, present ->
            val status =
                when {
                    present -> R.string.profile_readiness_added
                    index == 0 -> R.string.profile_readiness_recommended
                    else -> R.string.profile_readiness_field_optional
                }
            Text(
                stringResource(R.string.profile_readiness_field, stringResource(labels[index]), stringResource(status)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
