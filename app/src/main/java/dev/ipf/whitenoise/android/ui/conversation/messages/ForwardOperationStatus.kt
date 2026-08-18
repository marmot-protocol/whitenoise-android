package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.ForwardTargetProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.theme.Dimens
import java.util.Locale

internal const val FORWARD_OPERATION_STATUS_TEST_TAG = "forward-operation-status"
private const val FORWARD_TARGET_TITLE_FALLBACK_LENGTH = 12

@Composable
@Suppress("FunctionNaming")
internal fun ForwardOperationStatusHost(
    appState: WhiteNoiseAppState,
    modifier: Modifier = Modifier,
) {
    val snapshot by appState.activeForwardOperation.collectAsState()
    val current = snapshot ?: return
    val titleCopy = rememberGroupTitleCopy()
    val targetIds = current.targets.map(ForwardTargetProgress::groupIdHex)
    val titles =
        remember(targetIds, appState.forwardTargetMembersRevision, appState.profileRevisionForCompose, titleCopy) {
            val itemsById =
                appState.forwardTargets().associateBy { item ->
                    item.group.groupIdHex.lowercase(Locale.ROOT)
                }
            targetIds.associateWith { groupIdHex ->
                itemsById[groupIdHex.lowercase(Locale.ROOT)]?.let { item ->
                    chatListItemDisplayTitle(item, appState, titleCopy)
                } ?: groupIdHex.take(FORWARD_TARGET_TITLE_FALLBACK_LENGTH)
            }
        }
    ForwardOperationStatus(
        snapshot = current,
        targetTitles = titles,
        onCancel = { appState.cancelActiveForwardOperation() },
        onRetry = { appState.retryActiveForwardOperation() },
        onDismiss = { appState.dismissActiveForwardOperation() },
        modifier = modifier,
    )
}

/**
 * Persistent, non-modal forwarding chrome. Navigation remains usable while the
 * optional details sheet exposes the exact per-target transfer/send state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun ForwardOperationStatus(
    snapshot: ForwardOperationSnapshot,
    targetTitles: Map<String, String>,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var detailsVisible by rememberSaveable { mutableStateOf(false) }
    val summary = forwardOperationSummary(snapshot)

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag(FORWARD_OPERATION_STATUS_TEST_TAG)
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.titleSmall)
            if (snapshot.isActive) {
                LinearProgressIndicator(
                    progress = { forwardOperationProgress(snapshot) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ForwardOperationActions(
                snapshot = snapshot,
                onDetails = { detailsVisible = true },
                onCancel = onCancel,
                onRetry = onRetry,
                onDismiss = onDismiss,
            )
        }
    }

    if (detailsVisible) {
        ModalBottomSheet(onDismissRequest = { detailsVisible = false }) {
            ForwardProgressContent(
                snapshot = snapshot,
                targetTitles = targetTitles,
                modifier = Modifier.fillMaxWidth(),
            )
            ForwardOperationActions(
                snapshot = snapshot,
                onDetails = { detailsVisible = false },
                detailsLabel = stringResource(R.string.close),
                onCancel = onCancel,
                onRetry = onRetry,
                onDismiss = {
                    detailsVisible = false
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg, vertical = 12.dp),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ForwardOperationActions(
    snapshot: ForwardOperationSnapshot,
    onDetails: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    detailsLabel: String = stringResource(R.string.details),
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDetails) { Text(detailsLabel) }
        if (snapshot.canCancel) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        if (snapshot.canRetry) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
        if (!snapshot.isActive) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun forwardOperationSummary(snapshot: ForwardOperationSnapshot): String =
    when (snapshot.phase) {
        ForwardOperationPhase.Completed ->
            pluralStringResource(
                R.plurals.forward_completed_summary,
                snapshot.completedTargets,
                snapshot.completedTargets,
            )
        ForwardOperationPhase.PartialFailure ->
            stringResource(
                R.string.forward_partial_summary,
                snapshot.completedTargets,
                snapshot.targets.size,
            )
        ForwardOperationPhase.Failed -> stringResource(R.string.forward_failed_summary)
        ForwardOperationPhase.Cancelled -> stringResource(R.string.forward_cancelled)
        ForwardOperationPhase.Cancelling -> stringResource(R.string.forward_cancelling)
        ForwardOperationPhase.Preparing,
        ForwardOperationPhase.Running,
        -> stringResource(R.string.forward_progress_title)
    }

private fun forwardOperationProgress(snapshot: ForwardOperationSnapshot): Float {
    val completedWork =
        snapshot.preparedAttachments +
            snapshot.targets.sumOf { target -> target.uploadedAttachments + target.sentMessages }
    val totalWork =
        snapshot.totalAttachments +
            snapshot.targets.sumOf { target -> target.totalAttachments + target.totalMessages }
    return if (totalWork == 0) 0f else (completedWork.toFloat() / totalWork.toFloat()).coerceIn(0f, 1f)
}
