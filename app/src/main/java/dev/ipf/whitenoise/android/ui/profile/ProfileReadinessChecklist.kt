package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** A wrapping, neutral checklist of published fields; drafts never claim to have been published. */
@Composable
@Suppress("FunctionNaming")
internal fun ProfileReadinessChecklist(readiness: ProfileReadiness) {
    val labels =
        listOf(
            R.string.profile_readiness_name,
            R.string.profile_readiness_picture,
            R.string.profile_readiness_bio,
            R.string.profile_readiness_banner,
            R.string.profile_readiness_address,
            R.string.profile_readiness_lightning,
        )
    Column(Modifier.fillMaxWidth().testTag("profile.readiness"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(readiness.summary), style = MaterialTheme.typography.titleSmall)
        readiness.fields.forEachIndexed { index, present ->
            val status =
                when {
                    present -> R.string.profile_readiness_added
                    index == 0 -> R.string.profile_readiness_recommended
                    else -> R.string.profile_readiness_field_optional
                }
            Text(
                stringResource(R.string.profile_readiness_field, stringResource(labels[index]), stringResource(status)),
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
