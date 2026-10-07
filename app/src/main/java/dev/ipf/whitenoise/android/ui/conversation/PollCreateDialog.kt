package dev.ipf.whitenoise.android.ui.conversation

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MAX_POLL_DEADLINE_SECONDS
import dev.ipf.whitenoise.android.state.MAX_POLL_OPTIONS
import dev.ipf.whitenoise.android.state.POLL_DAY_SECONDS
import dev.ipf.whitenoise.android.state.POLL_FIVE_MINUTES_SECONDS
import dev.ipf.whitenoise.android.state.POLL_HOUR_SECONDS
import dev.ipf.whitenoise.android.state.POLL_WEEK_SECONDS
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import java.util.Locale

/** Edits a short poll draft; MDK remains the only owner of published poll state. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Dialog owns the draft and submit callback.
internal fun PollCreateDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, List<String>, PollTypeFfi, Long?, (Boolean) -> Unit) -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(listOf("", "")) }
    var multiple by rememberSaveable { mutableStateOf(false) }
    var presetDurationSeconds by rememberSaveable { mutableStateOf<Long?>(null) }
    var customSelected by rememberSaveable { mutableStateOf(false) }
    var customValue by rememberSaveable { mutableStateOf("") }
    var customUnitIndex by rememberSaveable { mutableStateOf(PollDurationUnit.MINUTES.ordinal) }
    val deadlineSelection =
        PollDeadlineSelection(
            presetSeconds = presetDurationSeconds,
            customSelected = customSelected,
            customValue = customValue,
            customUnit = PollDurationUnit.entries[customUnitIndex],
        )
    var submitting by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<PollDraftIssue?>(null) }
    var deadlineAttempted by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.poll_create)) },
        text = {
            PollCreateForm(
                question = question,
                options = options,
                multiple = multiple,
                deadlineSelection = deadlineSelection,
                enabled = !submitting,
                issue = issue,
                deadlineIssue =
                    if (deadlineSelection.customSelected &&
                        (deadlineAttempted || deadlineSelection.customValue.isNotBlank())
                    ) {
                        validatePollDeadlineSelection(deadlineSelection).issue
                    } else {
                        null
                    },
                onQuestionChange = {
                    question = it
                    issue = null
                },
                onOptionChange = { index, value ->
                    options = options.toMutableList().also { it[index] = value }
                    issue = null
                },
                onRemoveOption = { index ->
                    options = options.filterIndexed { i, _ -> i != index }
                    issue = null
                },
                onAddOption = {
                    options = options + ""
                    issue = null
                },
                onMultipleChange = { multiple = it },
                onDeadlineChange = {
                    presetDurationSeconds = it.presetSeconds
                    customSelected = it.customSelected
                    customValue = it.customValue
                    customUnitIndex = it.customUnit.ordinal
                },
            )
        },
        confirmButton = {
            Button(
                enabled = !submitting,
                onClick = {
                    val draft = validatePollDraft(question, options)
                    val deadline = validatePollDeadlineSelection(deadlineSelection)
                    issue = draft.issue
                    deadlineAttempted = true
                    if (draft.issue != null || deadline.issue != null) return@Button
                    submitting = true
                    onSubmit(
                        draft.question,
                        draft.options,
                        if (multiple) PollTypeFfi.MULTIPLE_CHOICE else PollTypeFfi.SINGLE_CHOICE,
                        deadline.durationSeconds,
                    ) { created ->
                        submitting = false
                        if (created) onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.poll_submit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Keeps the poll draft controls scrollable inside the dialog and in compact windows. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongParameterList", "LongMethod") // Form keeps all draft fields in one scrollable panel.
internal fun PollCreateForm(
    question: String,
    options: List<String>,
    multiple: Boolean,
    deadlineSelection: PollDeadlineSelection,
    enabled: Boolean,
    issue: PollDraftIssue? = null,
    deadlineIssue: PollDeadlineIssue? = null,
    onQuestionChange: (String) -> Unit,
    onOptionChange: (Int, String) -> Unit,
    onRemoveOption: (Int) -> Unit,
    onAddOption: () -> Unit,
    onMultipleChange: (Boolean) -> Unit,
    onDeadlineChange: (PollDeadlineSelection) -> Unit,
) {
    Column(
        Modifier.heightIn(max = 400.dp).fadingVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = question,
            onValueChange = onQuestionChange,
            label = { Text(stringResource(R.string.poll_question)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = enabled,
            isError = issue == PollDraftIssue.MISSING_QUESTION || issue == PollDraftIssue.QUESTION_TOO_LONG,
        )
        val questionIssue =
            when (issue) {
                PollDraftIssue.MISSING_QUESTION -> R.string.poll_enter_question
                PollDraftIssue.QUESTION_TOO_LONG -> R.string.poll_question_too_long
                else -> null
            }
        if (questionIssue != null) {
            Text(
                stringResource(questionIssue),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        options.forEachIndexed { index, value ->
            Row {
                OutlinedTextField(
                    value = value,
                    onValueChange = { onOptionChange(index, it) },
                    label = { Text(stringResource(R.string.poll_option, index + 1)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled,
                )
                if (index >= 2) {
                    IconButton(onClick = { onRemoveOption(index) }, enabled = enabled) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.poll_remove_option, index + 1),
                        )
                    }
                }
            }
        }
        val optionIssue =
            when (issue) {
                PollDraftIssue.TOO_FEW_OPTIONS -> R.string.poll_add_two_options
                PollDraftIssue.OPTION_TOO_LONG -> R.string.poll_option_too_long
                PollDraftIssue.DUPLICATE_OPTION -> R.string.poll_duplicate_option
                else -> null
            }
        if (optionIssue != null) {
            Text(
                stringResource(optionIssue),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (options.size < MAX_POLL_OPTIONS) {
            TextButton(onClick = onAddOption, enabled = enabled) {
                Text(stringResource(R.string.poll_add_option))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = !multiple,
                onClick = { onMultipleChange(false) },
                label = { Text(stringResource(R.string.poll_single_choice)) },
                enabled = enabled,
            )
            FilterChip(
                selected = multiple,
                onClick = { onMultipleChange(true) },
                label = { Text(stringResource(R.string.poll_multiple_choice)) },
                enabled = enabled,
            )
        }
        PollDeadlineChoices(deadlineSelection, enabled, deadlineIssue, onDeadlineChange)
    }
}

/** Lets the creator choose a preset or a validated custom duration. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming", "LongMethod") // The picker keeps its preset and custom controls together.
private fun PollDeadlineChoices(
    selection: PollDeadlineSelection,
    enabled: Boolean,
    issue: PollDeadlineIssue?,
    onSelect: (PollDeadlineSelection) -> Unit,
) {
    Text(stringResource(R.string.poll_deadline))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            null to R.string.poll_no_deadline,
            POLL_FIVE_MINUTES_SECONDS to R.string.poll_duration_5_minutes,
            POLL_HOUR_SECONDS to R.string.poll_duration_1_hour,
            POLL_DAY_SECONDS to R.string.poll_duration_1_day,
            POLL_WEEK_SECONDS to R.string.poll_duration_1_week,
            MAX_POLL_DEADLINE_SECONDS to R.string.poll_30_days,
        ).forEach { (duration, label) ->
            FilterChip(
                selected = !selection.customSelected && selection.presetSeconds == duration,
                onClick = { onSelect(selection.copy(presetSeconds = duration, customSelected = false)) },
                label = {
                    Text(
                        stringResource(label),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    )
                },
                enabled = enabled,
            )
        }
        FilterChip(
            selected = selection.customSelected,
            onClick = { onSelect(selection.copy(customSelected = true)) },
            label = { Text(stringResource(R.string.poll_custom_time)) },
            enabled = enabled,
        )
    }
    if (selection.customSelected) {
        OutlinedTextField(
            value = selection.customValue,
            onValueChange = { onSelect(selection.copy(customValue = it)) },
            label = { Text(stringResource(R.string.poll_custom_duration)) },
            modifier = Modifier.fillMaxWidth().testTag("poll-custom-duration"),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = enabled,
            isError = issue != null,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PollDurationUnit.entries.forEach { unit ->
                FilterChip(
                    selected = selection.customUnit == unit,
                    onClick = { onSelect(selection.copy(customUnit = unit)) },
                    label = { Text(stringResource(unit.labelRes)) },
                    enabled = enabled,
                )
            }
        }
        if (issue != null) {
            Text(
                stringResource(R.string.poll_custom_duration_error),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
            )
        } else {
            val duration = validatePollDeadlineSelection(selection).durationSeconds
            if (duration != null) {
                val locale = LocalConfiguration.current.locales[0]
                Text(
                    stringResource(R.string.poll_custom_preview, formatPollDuration(duration, locale)),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                )
            }
        }
        TextButton(
            onClick = { onSelect(selection.copy(customSelected = false)) },
            enabled = enabled,
        ) { Text(stringResource(R.string.poll_cancel_custom_time)) }
    }
}

/** Formats the selected duration in full localized units without starting its countdown. */
private fun formatPollDuration(
    seconds: Long,
    locale: Locale,
): String {
    var remaining = seconds
    val measures =
        listOf(
            MeasureUnit.DAY to POLL_DAY_SECONDS,
            MeasureUnit.HOUR to POLL_HOUR_SECONDS,
            MeasureUnit.MINUTE to PollDurationUnit.MINUTES.seconds,
            MeasureUnit.SECOND to 1L,
        ).mapNotNull { (unit, unitSeconds) ->
            val count = remaining / unitSeconds
            remaining %= unitSeconds
            if (count > 0L) Measure(count, unit) else null
        }
    val formatter = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
    return formatter.formatMeasures(*measures.toTypedArray())
}
