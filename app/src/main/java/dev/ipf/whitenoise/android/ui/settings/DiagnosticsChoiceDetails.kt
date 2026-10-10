@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ExpandLess
import dev.ipf.whitenoise.android.ui.icons.filled.ExpandMore

/** Disclosure controls are separate from the switch: reading details never grants consent. */
@Composable
internal fun DiagnosticsChoiceDetails(
    title: String,
    tag: String,
    details: List<Int>,
    choice: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val action = stringResource(if (expanded) R.string.setup_hide_details else R.string.setup_details)
    val label = stringResource(R.string.onboarding_details_accessibility, title, action)
    val state =
        stringResource(
            if (expanded) R.string.onboarding_details_expanded else R.string.onboarding_details_collapsed,
        )
    Column(Modifier.fillMaxWidth()) {
        choice()
        TextButton(
            onClick = { expanded = !expanded },
            modifier =
                Modifier
                    .padding(horizontal = 8.dp)
                    .testTag(tag)
                    .semantics {
                        contentDescription = label
                        stateDescription = state
                    },
        ) {
            Text(action)
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                details.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
