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
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MAX_POLL_OPTIONS

/** Edits a short poll draft; MDK remains the only owner of published poll state. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PollCreateDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, List<String>, PollTypeFfi, (Boolean) -> Unit) -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    var options by rememberSaveable { mutableStateOf(listOf("", "")) }
    var multiple by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val valid = question.isNotBlank() && options.size in 2..MAX_POLL_OPTIONS && options.all { it.isNotBlank() }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.poll_create)) },
        text = {
            PollCreateForm(
                question = question,
                options = options,
                multiple = multiple,
                enabled = !submitting,
                onQuestionChange = { question = it },
                onOptionChange = { index, value -> options = options.toMutableList().also { it[index] = value } },
                onRemoveOption = { index -> options = options.filterIndexed { i, _ -> i != index } },
                onAddOption = { options = options + "" },
                onMultipleChange = { multiple = it },
            )
        },
        confirmButton = {
            Button(
                enabled = valid && !submitting,
                onClick = {
                    submitting = true
                    onSubmit(
                        question,
                        options,
                        if (multiple) PollTypeFfi.MULTIPLE_CHOICE else PollTypeFfi.SINGLE_CHOICE,
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
@Suppress("FunctionNaming", "LongParameterList") // Form callbacks keep draft state in the dialog.
internal fun PollCreateForm(
    question: String,
    options: List<String>,
    multiple: Boolean,
    enabled: Boolean,
    onQuestionChange: (String) -> Unit,
    onOptionChange: (Int, String) -> Unit,
    onRemoveOption: (Int) -> Unit,
    onAddOption: () -> Unit,
    onMultipleChange: (Boolean) -> Unit,
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
        )
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
    }
}
