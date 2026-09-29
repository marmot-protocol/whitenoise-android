package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
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
    var deadlineDurationSeconds by rememberSaveable { mutableStateOf<Long?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<PollDraftIssue?>(null) }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.poll_create)) },
        text = {
            PollCreateForm(
                question = question,
                options = options,
                multiple = multiple,
                deadlineDurationSeconds = deadlineDurationSeconds,
                enabled = !submitting,
                issue = issue,
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
                onDeadlineChange = { deadlineDurationSeconds = it },
            )
        },
        confirmButton = {
            Button(
                enabled = !submitting,
                onClick = {
                    val draft = validatePollDraft(question, options)
                    issue = draft.issue
                    if (draft.issue != null) return@Button
                    submitting = true
                    onSubmit(
                        draft.question,
                        draft.options,
                        if (multiple) PollTypeFfi.MULTIPLE_CHOICE else PollTypeFfi.SINGLE_CHOICE,
                        deadlineDurationSeconds,
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
    deadlineDurationSeconds: Long?,
    enabled: Boolean,
    issue: PollDraftIssue? = null,
    onQuestionChange: (String) -> Unit,
    onOptionChange: (Int, String) -> Unit,
    onRemoveOption: (Int) -> Unit,
    onAddOption: () -> Unit,
    onMultipleChange: (Boolean) -> Unit,
    onDeadlineChange: (Long?) -> Unit,
) {
    Column(
        Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
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
        PollDeadlineChoices(deadlineDurationSeconds, enabled, onDeadlineChange)
    }
}

/** Lets the creator choose when a new poll will close, including no deadline. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun PollDeadlineChoices(
    selectedDurationSeconds: Long?,
    enabled: Boolean,
    onSelect: (Long?) -> Unit,
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
                selected = selectedDurationSeconds == duration,
                onClick = { onSelect(duration) },
                label = {
                    Text(
                        stringResource(label),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
                    )
                },
                enabled = enabled,
            )
        }
    }
}
