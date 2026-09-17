package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.REPORT_EXPLANATION_LIMIT
import dev.ipf.whitenoise.android.state.REPORT_REASONS
import dev.ipf.whitenoise.android.state.boundedExplanation
import dev.ipf.whitenoise.android.state.reportReasonLabel

internal const val REPORT_SHEET_TEST_TAG = "message.report"

/**
 * Reports one message to the group's admins. The reader picks a reason and may add an explanation, both of
 * which MarmotKit publishes encrypted to the group; the copy says so, because a report is not anonymous.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun ReportMessageSheet(
    onDismissRequest: () -> Unit,
    onSubmit: (ReportReasonFfi, String) -> Unit,
    sending: Boolean = false,
) {
    var reason by remember { mutableStateOf(REPORT_REASONS.first()) }
    var explanation by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(REPORT_SHEET_TEST_TAG),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.report_message_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.report_message_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.selectableGroup()) {
                REPORT_REASONS.forEach { candidate ->
                    ReportReasonRow(
                        label = stringResource(reportReasonLabel(candidate)),
                        selected = candidate == reason,
                        onSelect = { reason = candidate },
                    )
                }
            }
            OutlinedTextField(
                value = explanation,
                onValueChange = { explanation = it.take(REPORT_EXPLANATION_LIMIT) },
                label = { Text(stringResource(R.string.report_message_explanation_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("message.report.explanation"),
                singleLine = false,
                minLines = 2,
            )
            Button(
                onClick = { onSubmit(reason, boundedExplanation(explanation)) },
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().testTag("message.report.send"),
            ) {
                Text(stringResource(R.string.report_message_send))
            }
        }
    }
}

/** One reason choice; the whole row is the target so the label is as tappable as the button. */
@Suppress("FunctionNaming")
@Composable
private fun ReportReasonRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
