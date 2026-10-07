package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.ReportReasonFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.REPORT_EXPLANATION_LIMIT
import dev.ipf.whitenoise.android.state.REPORT_REASONS
import dev.ipf.whitenoise.android.state.boundedExplanation
import dev.ipf.whitenoise.android.state.reportReasonLabel
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll

internal const val REPORT_SHEET_TEST_TAG = "message.report"
internal const val REPORT_BODY_TEST_TAG = "message.report.body"

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
        dragHandle = { ReportMessageDragHandle() },
    ) {
        ReportMessageForm(reason, { reason = it }, explanation, { explanation = it }, sending, onSubmit)
    }
}

/** Retains Material's drag actions while leaving a usable body when a tall keyboard crowds the window. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
private fun ReportMessageDragHandle() {
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val availableHeight =
        with(density) {
            (windowHeight - insets.getTop(this) - insets.getBottom(this)).coerceAtLeast(0).toDp()
        }
    if (availableHeight < 240.dp * density.fontScale) {
        val dismissLabel = stringResource(R.string.dismiss)
        Surface(
            modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = dismissLabel },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Box(Modifier.size(width = 32.dp, height = 4.dp))
        }
    } else {
        BottomSheetDefaults.DragHandle()
    }
}

/** Measures the action first, then lets only the form body scroll within the sheet's inset-aware viewport. */
@Suppress("FunctionNaming")
@Composable
internal fun ReportMessageForm(
    reason: ReportReasonFfi,
    onReasonChange: (ReportReasonFfi) -> Unit,
    explanation: String,
    onExplanationChange: (String) -> Unit,
    sending: Boolean,
    onSubmit: (ReportReasonFfi, String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxHeight < 160.dp * LocalDensity.current.fontScale
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = if (compact) 4.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp),
        ) {
            ReportMessageBody(
                reason,
                onReasonChange,
                explanation,
                onExplanationChange,
                Modifier.weight(1f, fill = false),
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

/** Scroll ownership stays in the body while the outer form measures its Send action independently. */
@Suppress("FunctionNaming")
@Composable
private fun ReportMessageBody(
    reason: ReportReasonFfi,
    onReasonChange: (ReportReasonFfi) -> Unit,
    explanation: String,
    onExplanationChange: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).testTag(REPORT_BODY_TEST_TAG),
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
                    onSelect = { onReasonChange(candidate) },
                )
            }
        }
        OutlinedTextField(
            value = explanation,
            onValueChange = { onExplanationChange(it.take(REPORT_EXPLANATION_LIMIT)) },
            label = { Text(stringResource(R.string.report_message_explanation_hint)) },
            modifier = Modifier.fillMaxWidth().testTag("message.report.explanation"),
            singleLine = false,
            minLines = 2,
        )
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
