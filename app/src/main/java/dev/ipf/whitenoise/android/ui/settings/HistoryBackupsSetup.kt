package dev.ipf.whitenoise.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HistoryBackupAction
import dev.ipf.whitenoise.android.state.HistoryBackupsState
import dev.ipf.whitenoise.android.state.RecoveryWordsSetup
import dev.ipf.whitenoise.android.ui.common.COPIED_FEEDBACK_MILLIS
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.common.copySensitiveToClipboard
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.delay

/** The recovery-words setup flow of History backups (#3655): show the twelve words once, then confirm two. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun RecoveryWordsSetupContent(
    setup: RecoveryWordsSetup,
    state: HistoryBackupsState,
    actions: HistoryBackupsActions,
) {
    val first = remember(setup.confirmPositions) { TextFieldState() }
    val second = remember(setup.confirmPositions) { TextFieldState() }
    DisposableEffect(first, second) {
        onDispose {
            first.clearText()
            second.clearText()
        }
    }
    // Leaving setup forgets the words; nothing is dismissed while a confirmation is being sent.
    BackHandler(enabled = state.running == HistoryBackupAction.Confirm) {}
    BackHandler(enabled = state.running != HistoryBackupAction.Confirm, onBack = actions.onCancelSetup)
    val title = if (setup.confirming) R.string.history_backups_confirm_title else R.string.history_backups_words_title
    SettingsScaffold(
        title = stringResource(title),
        onBack = { if (state.running != HistoryBackupAction.Confirm) actions.onCancelSetup() },
    ) {
        SettingsList(modifier = Modifier.testTag("history_backups.setup")) {
            if (!setup.confirming) {
                item(key = "words.message") {
                    SettingsExplainer(stringResource(R.string.history_backups_words_message))
                }
                item(key = "words.grid") { RecoveryWordsGrid(setup.words) }
                item(key = "words.copy") { CopyRecoveryWordsRow(setup.words) }
                item(key = "words.actions") {
                    SetupButtons(
                        primary = stringResource(R.string.history_backups_words_written),
                        onPrimary = actions.onWordsWritten,
                        primaryEnabled = true,
                        secondary = stringResource(R.string.cancel),
                        onSecondary = actions.onCancelSetup,
                        primaryTag = "history_backups.words_written",
                    )
                }
            } else {
                val (a, b) = setup.confirmPositions
                item(key = "confirm.message") {
                    SettingsExplainer(stringResource(R.string.history_backups_confirm_message, a, b))
                }
                item(key = "confirm.fields") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
                        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                    ) {
                        RecoveryWordsInput(
                            state = first,
                            label = stringResource(R.string.history_backups_word_label, a),
                            enabled = state.running == null,
                            modifier = Modifier.fillMaxWidth().testTag("history_backups.confirm_first"),
                        )
                        RecoveryWordsInput(
                            state = second,
                            label = stringResource(R.string.history_backups_word_label, b),
                            enabled = state.running == null,
                            modifier = Modifier.fillMaxWidth().testTag("history_backups.confirm_second"),
                        )
                    }
                }
                state.problem?.let { problem ->
                    item(key = "confirm.problem") {
                        SettingsCallout(
                            text = stringResource(historyBackupProblemText(problem)),
                            isError = true,
                            modifier =
                                Modifier
                                    .testTag("history_backups.problem")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                item(key = "confirm.actions") {
                    SetupButtons(
                        primary = stringResource(R.string.history_backups_turn_on),
                        onPrimary = { actions.onConfirm(listOf(first.text.toString(), second.text.toString())) },
                        primaryEnabled = state.running == null && first.text.isNotBlank() && second.text.isNotBlank(),
                        primaryLoading = state.running == HistoryBackupAction.Confirm,
                        secondary = stringResource(R.string.cancel),
                        onSecondary = actions.onCancelSetup,
                        secondaryEnabled = state.running == null,
                        primaryTag = "history_backups.confirm",
                    )
                }
            }
        }
    }
}

/** The twelve words in reading order, two columns of six, numbered as the user writes them down. */
@Suppress("FunctionNaming")
@Composable
private fun RecoveryWordsGrid(words: List<String>) {
    val half = (words.size + 1) / 2
    SettingsPanel(
        modifier = Modifier.testTag("history_backups.words"),
        destination = SettingsDetail.HistoryBackups,
        surface = "history-backups-words",
    ) {
        Row(Modifier.fillMaxWidth().padding(WhiteNoiseSpacing.FormField)) {
            for (column in 0 until 2) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
                    for (index in column * half until minOf(words.size, (column + 1) * half)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = (index + 1).toString(),
                                modifier = Modifier.widthIn(min = 28.dp).padding(end = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(words[index], style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Copies the words in order, separated by spaces, for a password manager. Like the private key on Account keys, the
 * clip is marked sensitive and cleared about a minute later.
 */
@Suppress("FunctionNaming")
@Composable
private fun CopyRecoveryWordsRow(words: List<String>) {
    val context = LocalContext.current
    val label = stringResource(R.string.history_backups)
    var copied by remember(words) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MILLIS)
            copied = false
        }
    }
    SettingsGroup {
        row("copy_words") { row ->
            SettingsAction(
                context = row,
                title = stringResource(if (copied) R.string.copied else R.string.history_backups_copy_words),
                onClick = {
                    copySensitiveToClipboard(context, label, words.joinToString(" "))
                    copied = true
                },
                modifier = Modifier.testTag("history_backups.copy_words"),
                leading = { SettingsLeadingIcon(copyGlyph(copied)) },
            )
        }
    }
}

/** Shows the current setup action and its cancel control. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
private fun SetupButtons(
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean,
    secondary: String,
    onSecondary: () -> Unit,
    primaryTag: String,
    primaryLoading: Boolean = false,
    secondaryEnabled: Boolean = true,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        WhiteNoiseButton(
            onClick = onPrimary,
            enabled = primaryEnabled,
            loading = primaryLoading,
            loadingLabel = primary,
            modifier = Modifier.fillMaxWidth().testTag(primaryTag),
        ) { Text(primary) }
        WhiteNoiseOutlinedButton(
            onClick = onSecondary,
            enabled = secondaryEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(secondary) }
    }
}

/**
 * A field for recovery words. The password keyboard type stops suggestions and learning on top of the app-wide
 * incognito keyboard, and nothing here is saveable, so the words stay out of the saved instance state.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun RecoveryWordsInput(
    state: TextFieldState,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    multiLine: Boolean = false,
) {
    WhiteNoiseTextField(
        state = state,
        enabled = enabled,
        modifier = modifier,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        errorMessage = error,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (multiLine) ImeAction.Default else ImeAction.Next,
            ),
        lineLimits =
            if (multiLine) TextFieldLineLimits.MultiLine(minHeightInLines = 3) else TextFieldLineLimits.SingleLine,
    )
}
