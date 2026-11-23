@file:Suppress("TooManyFunctions", "MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.ipf.marmotkit.BackupRestorePhaseFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HistoryBackupAction
import dev.ipf.whitenoise.android.state.HistoryBackupProblem
import dev.ipf.whitenoise.android.state.HistoryBackupStatus
import dev.ipf.whitenoise.android.state.HistoryBackupsController
import dev.ipf.whitenoise.android.state.HistoryBackupsState
import dev.ipf.whitenoise.android.state.HistoryRestoreProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.historyBackupsAccess
import dev.ipf.whitenoise.android.state.signedInAccountEligible
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** What the History backups screen can ask its controller to do. */
internal class HistoryBackupsActions(
    val onTurnOn: () -> Unit = {},
    val onWordsWritten: () -> Unit = {},
    val onConfirm: (List<String>) -> Unit = {},
    val onCancelSetup: () -> Unit = {},
    val onTurnOff: () -> Unit = {},
    val onRestore: (String) -> Unit = {},
    val onCancelRestore: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Observable account and runtime changes replace the screen's controller; leaving forgets unconfirmed words. */
@Composable
internal fun rememberHistoryBackupsController(appState: WhiteNoiseAppState): HistoryBackupsController {
    val scope = rememberCoroutineScope()
    val eligible = appState.signedInAccountEligible()
    val controller =
        remember(appState.activeAccountRef, appState.runtimeGeneration, eligible) {
            HistoryBackupsController(appState.historyBackupsAccess(), scope, cleanupScope = appState.mutationsScope)
        }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) {
        controller.refresh()
        controller.watchRestore()
    }
    return controller
}

/**
 * Settings → Account → History backups (#3655). The whole screen blocks screenshots because it shows or takes
 * recovery words.
 */
@Suppress("FunctionNaming")
@Composable
internal fun HistoryBackupsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    WindowSecureFlag()
    val controller = rememberHistoryBackupsController(appState)
    // Keyed by controller: an account or runtime change disposes every input and state of the old owner.
    key(controller) {
        val state by controller.state.collectAsState()
        val actions =
            remember(controller) {
                HistoryBackupsActions(
                    onTurnOn = controller::beginSetup,
                    onWordsWritten = controller::continueToConfirm,
                    onConfirm = controller::confirm,
                    onCancelSetup = controller::cancelSetup,
                    onTurnOff = controller::turnOff,
                    onRestore = controller::beginRestore,
                    onCancelRestore = controller::cancelRestore,
                    onRetry = controller::refresh,
                )
            }
        HistoryBackupsContent(state, actions, onBack)
    }
}

@Suppress("FunctionNaming")
@Composable
internal fun HistoryBackupsContent(
    state: HistoryBackupsState,
    actions: HistoryBackupsActions,
    onBack: () -> Unit,
) {
    val setup = state.setup
    if (setup != null) {
        RecoveryWordsSetupContent(setup, state, actions)
        return
    }
    var confirmTurnOff by remember { mutableStateOf(false) }
    // Typed restore words are composition-only: never saveable, cleared when this content leaves.
    val restoreWords = remember { TextFieldState() }
    val restoreOperation = state.restore?.operation
    LaunchedEffect(restoreOperation) {
        if (restoreOperation != null) restoreWords.clearText()
    }
    DisposableEffect(restoreWords) { onDispose { restoreWords.clearText() } }
    SettingsScaffold(title = stringResource(R.string.history_backups), onBack = onBack) {
        SettingsList(modifier = Modifier.testTag("history_backups.list")) {
            item(key = "search.HistoryBackups.1") {
                SettingsExplainer(stringResource(R.string.history_backups_explanation))
            }
            item(key = "search.HistoryBackups.2") {
                HistoryBackupsStatusGroup(state, actions, onTurnOff = { confirmTurnOff = true })
            }
            historyBackupsCallouts(state)
            if (state.status != HistoryBackupStatus.Unavailable && state.status != HistoryBackupStatus.Loading) {
                item(key = "search.HistoryBackups.3") {
                    SettingsSection(stringResource(R.string.history_backups_restore_section))
                }
                item(key = "search.HistoryBackups.4") { HistoryRestoreGroup(state, restoreWords, actions) }
            }
        }
    }
    if (confirmTurnOff) {
        ConfirmDialog(
            title = stringResource(R.string.history_backups_turn_off_title),
            message = stringResource(R.string.history_backups_turn_off_message),
            confirmLabel = stringResource(R.string.history_backups_turn_off),
            destructive = true,
            onConfirm = {
                confirmTurnOff = false
                actions.onTurnOff()
            },
            onDismiss = { confirmTurnOff = false },
        )
    }
}

/** Shows the backup status and actions available for the current account. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun HistoryBackupsStatusGroup(
    state: HistoryBackupsState,
    actions: HistoryBackupsActions,
    onTurnOff: () -> Unit,
) {
    val idle = state.running == null
    val status = state.status
    SettingsGroup(
        modifier = Modifier.testTag("history_backups.group"),
        destination = SettingsDetail.HistoryBackups,
        surface = "history-backups-controls",
    ) {
        row("status") { context ->
            SettingsValue(
                context = context,
                title = stringResource(R.string.history_backups_status),
                value = stringResource(historyBackupStatusText(status)),
                modifier = Modifier.testTag("history_backups.status"),
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_backup_restore) },
            )
        }
        when (status) {
            HistoryBackupStatus.Off ->
                row("turn_on") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.history_backups_turn_on),
                        subtitle = stringResource(R.string.history_backups_turn_on_detail),
                        onClick = actions.onTurnOn,
                        enabled = idle,
                        modifier = Modifier.testTag("history_backups.turn_on"),
                        leading = { SettingsLeadingIcon(R.drawable.ic_check) },
                    )
                }
            is HistoryBackupStatus.On -> {
                row("new_words") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.history_backups_new_words),
                        subtitle = stringResource(R.string.history_backups_new_words_detail),
                        onClick = actions.onTurnOn,
                        enabled = idle,
                        modifier = Modifier.testTag("history_backups.new_words"),
                        leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                    )
                }
                row("turn_off") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.history_backups_turn_off),
                        onClick = onTurnOff,
                        enabled = idle,
                        destructive = true,
                        modifier = Modifier.testTag("history_backups.turn_off"),
                        leading = { SettingsLeadingIcon(R.drawable.ic_block) },
                    )
                }
            }
            HistoryBackupStatus.LoadFailed ->
                row("retry") { context ->
                    SettingsAction(
                        context = context,
                        title = stringResource(R.string.history_backups_retry),
                        onClick = actions.onRetry,
                        enabled = idle,
                        modifier = Modifier.testTag("history_backups.retry"),
                        leading = { SettingsLeadingIcon(R.drawable.ic_refresh) },
                    )
                }
            HistoryBackupStatus.Loading, HistoryBackupStatus.Unavailable -> Unit
        }
    }
}

/** Displays account-level guidance above the restore controls. */
private fun LazyListScope.historyBackupsCallouts(state: HistoryBackupsState) {
    if (state.status == HistoryBackupStatus.Unavailable) {
        item(key = "history_backups.unavailable") {
            SettingsCallout(
                text = stringResource(R.string.history_backups_unavailable),
                modifier = Modifier.testTag("history_backups.unavailable"),
            )
        }
    }
    if ((state.status as? HistoryBackupStatus.On)?.thisDeviceWrites == true) {
        item(key = "history_backups.schedule") { SettingsExplainer(stringResource(R.string.history_backups_schedule)) }
    }
    val problem = state.problem
    if (problem != null &&
        problem != HistoryBackupProblem.Unavailable &&
        problem != HistoryBackupProblem.InvalidRestoreWords
    ) {
        item(key = "history_backups.problem") {
            SettingsCallout(
                text = stringResource(historyBackupProblemText(problem)),
                isError = true,
                modifier = Modifier.testTag("history_backups.problem").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Shows restore input or progress with the matching actions. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
private fun HistoryRestoreGroup(
    state: HistoryBackupsState,
    words: TextFieldState,
    actions: HistoryBackupsActions,
) {
    val restore = state.restore
    val running = restore?.running == true
    // Fields sit on the screen canvas, like other settings forms, so their filled containers stay visible.
    Column(
        Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(
            stringResource(R.string.history_backups_restore_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (restore != null) {
            Text(
                text = historyRestoreProgressText(restore),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (restore.phase == BackupRestorePhaseFfi.PARTIAL) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier =
                    Modifier
                        .testTag("history_backups.restore_progress")
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (running) {
            WhiteNoiseOutlinedButton(
                onClick = actions.onCancelRestore,
                enabled = state.running == null,
                modifier = Modifier.fillMaxWidth().testTag("history_backups.cancel_restore"),
            ) { Text(stringResource(R.string.history_backups_cancel_restore)) }
        } else {
            RecoveryWordsInput(
                state = words,
                label = stringResource(R.string.history_backups_restore_words),
                supporting = stringResource(R.string.history_backups_restore_words_help),
                error =
                    if (state.problem == HistoryBackupProblem.InvalidRestoreWords) {
                        stringResource(R.string.history_backups_problem_restore_words)
                    } else {
                        null
                    },
                enabled = state.running == null,
                multiLine = true,
                modifier = Modifier.fillMaxWidth().testTag("history_backups.restore_words"),
            )
            val starting = state.running == HistoryBackupAction.BeginRestore
            WhiteNoiseButton(
                onClick = { actions.onRestore(words.text.toString()) },
                enabled = state.running == null && hasTwelveRecoveryWords(words.text),
                loading = starting,
                loadingLabel = stringResource(R.string.history_backups_restoring),
                modifier = Modifier.fillMaxWidth().testTag("history_backups.restore"),
            ) { Text(stringResource(R.string.history_backups_restore)) }
        }
    }
}

private const val RECOVERY_WORD_COUNT = 12

/** MDK validates spelling and checksum. Keep incomplete entries on the form. */
private fun hasTwelveRecoveryWords(text: CharSequence): Boolean =
    text.indices.count { index ->
        !text[index].isWhitespace() && (index == 0 || text[index - 1].isWhitespace())
    } == RECOVERY_WORD_COUNT

/** Selects the localized label for the current backup status. */
internal fun historyBackupStatusText(status: HistoryBackupStatus): Int =
    when (status) {
        HistoryBackupStatus.Loading -> R.string.history_backups_status_loading
        HistoryBackupStatus.Unavailable -> R.string.history_backups_status_unavailable
        HistoryBackupStatus.Off -> R.string.history_backups_status_off
        is HistoryBackupStatus.On ->
            if (status.thisDeviceWrites) {
                R.string.history_backups_status_on_this_device
            } else {
                R.string.history_backups_status_on_other_device
            }
        HistoryBackupStatus.LoadFailed -> R.string.history_backups_status_failed
    }

/** Selects localized guidance for a backup or restore failure. */
internal fun historyBackupProblemText(problem: HistoryBackupProblem): Int =
    when (problem) {
        HistoryBackupProblem.Unavailable -> R.string.history_backups_unavailable
        HistoryBackupProblem.WrongWords -> R.string.history_backups_problem_wrong_words
        HistoryBackupProblem.Busy -> R.string.history_backups_problem_busy
        HistoryBackupProblem.RosterUnavailable -> R.string.history_backups_problem_roster
        HistoryBackupProblem.InvalidRestoreWords -> R.string.history_backups_problem_restore_words
        HistoryBackupProblem.RestoreBusy -> R.string.history_backups_problem_restore_busy
        HistoryBackupProblem.SetupExpired -> R.string.history_backups_problem_expired
        HistoryBackupProblem.Failed -> R.string.history_backups_problem_failed
    }

/** Formats the current restore phase and message counts. */
@Composable
private fun historyRestoreProgressText(restore: HistoryRestoreProgress): String {
    val count = restore.importedMessages.toInt()
    return when (restore.phase) {
        BackupRestorePhaseFfi.IDLE, BackupRestorePhaseFfi.DISCOVERING ->
            stringResource(R.string.history_restore_discovering)
        BackupRestorePhaseFfi.RESTORING -> stringResource(R.string.history_restore_restoring, count)
        BackupRestorePhaseFfi.COMPLETE -> stringResource(R.string.history_restore_complete, count)
        BackupRestorePhaseFfi.PARTIAL -> stringResource(R.string.history_restore_partial, count)
        BackupRestorePhaseFfi.UNAVAILABLE -> stringResource(R.string.history_restore_unavailable)
        BackupRestorePhaseFfi.CANCELLED -> stringResource(R.string.history_restore_cancelled, count)
    }
}
