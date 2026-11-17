@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WipeReport
import dev.ipf.whitenoise.android.state.WipeStage
import dev.ipf.whitenoise.android.state.WipeStageReport
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.ErrorOutline
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/**
 * Non-cancellable staged progress sheet shown while the engine's
 * `signOutAndWipe` runs (#350). The FFI is a single suspend call that reports
 * per-stage results only in its final [dev.ipf.marmotkit.WipeOutcomeFfi] \u2014
 * there is no streaming progress \u2014 so this deliberately renders all three
 * stages as in-flight with indeterminate expressive indicators rather than
 * faking real-time per-stage advancement; the stages are marked from the
 * outcome afterwards (partial-failure sheet, or success toast). Hosted at the
 * app root because the wipe pops the screen that started it mid-flight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WipeProgressSheet() {
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        // The teardown cannot be cancelled: swallow scrim taps, reject the
        // hide gesture, keep back from dismissing, and drop the drag handle so
        // the sheet doesn't advertise a dismissal it won't honor.
        onDismissRequest = {},
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden },
            ),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        dragHandle = null,
    ) {
        // Large text can push the keep-open note past the screen, so the sheet scrolls.
        Column(
            Modifier.fillMaxWidth().fadingVerticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.wipe_progress_title), style = MaterialTheme.typography.titleLarge)
            WipeProgressStageRow(stringResource(R.string.wipe_stage_leaving_groups))
            WipeProgressStageRow(stringResource(R.string.wipe_stage_deleting_key_packages))
            WipeProgressStageRow(stringResource(R.string.wipe_stage_wiping_local_data))
            // Leaving a chat with another admin first steps this account down, which can wait on relays.
            Text(
                stringResource(R.string.wipe_progress_keep_open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WipeProgressStageRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(modifier = Modifier.size(28.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Post-wipe partial-failure sheet (#350): "Wipe finished with N issues", one
 * row per engine stage with its best-effort failures. Renders only the mapped
 * [WipeReport] snapshot \u2014 the wiped account's ref is invalid by the time this
 * shows, so nothing here may reach back into the FFI (see #956). Shown over
 * the post-wipe end state (next account's chat list, or onboarding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WipeOutcomeSheet(
    report: WipeReport,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.wipe_finished_with_issues, report.issueCount, report.issueCount),
                style = MaterialTheme.typography.titleLarge,
            )
            report.stages.forEach { stage -> WipeOutcomeStageRow(stage) }
            // Chats handed to the account's other devices are not counted as left, a failed handoff is said plainly.
            if (report.chatsLeftToOtherDevices > 0) {
                Text(stringResource(R.string.wipe_report_left_to_other_devices, report.chatsLeftToOtherDevices))
            }
            if (report.otherDevicesNotReached) {
                Text(
                    stringResource(R.string.wipe_report_other_devices_not_reached),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                ProfileKeyActionLabel(stringResource(R.string.close), R.drawable.ic_close)
            }
        }
    }
}

@Composable
private fun WipeOutcomeStageRow(stage: WipeStageReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            if (stage.hasIssues) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
            contentDescription = null,
            tint = if (stage.hasIssues) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(wipeOutcomeStageSummary(stage))
            stage.failures.forEach { failure ->
                Text(
                    listOfNotNull(failure.subject, failure.reason.takeIf { it.isNotBlank() }).joinToString(" \u2014 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@Suppress("MaxLineLength")
private fun wipeOutcomeStageSummary(stage: WipeStageReport): String =
    when (stage.stage) {
        WipeStage.LeavingGroups ->
            stringResource(R.string.wipe_outcome_groups_left, stage.completedCount ?: 0)
        WipeStage.DeletingKeyPackages ->
            stringResource(R.string.wipe_outcome_key_packages_deleted, stage.completedCount ?: 0)
        WipeStage.WipingLocalData ->
            stringResource(
                if (stage.hasIssues) R.string.wipe_outcome_local_wipe_incomplete else R.string.wipe_outcome_local_wipe_done,
            )
    }
