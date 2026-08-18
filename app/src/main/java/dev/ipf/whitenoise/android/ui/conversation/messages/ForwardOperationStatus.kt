package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
 * Compact, non-modal forwarding activity strip. Navigation remains usable while
 * the optional details sheet exposes the exact per-target transfer/send state.
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
    val animatedProgress by
        animateFloatAsState(
            targetValue = forwardOperationProgress(snapshot),
            animationSpec = tween(durationMillis = 200),
            label = "forwardOperationProgress",
        )

    Surface(
        tonalElevation = 3.dp,
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(FORWARD_OPERATION_STATUS_TEST_TAG)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.details),
                                onClick = { detailsVisible = true },
                            ).padding(start = Dimens.spaceLg, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ForwardOperationStatusIcon(snapshot)
                    Text(
                        text = summary,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (snapshot.isActive && snapshot.targets.size > 1) {
                        Text(
                            text = "${snapshot.completedTargets}/${snapshot.targets.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (snapshot.isActive) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (snapshot.canRetry) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                if (!snapshot.isActive) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss),
                        )
                    }
                }
            }
            if (snapshot.isActive) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
                )
            }
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
private fun ForwardOperationStatusIcon(snapshot: ForwardOperationSnapshot) {
    when (snapshot.phase) {
        ForwardOperationPhase.Preparing,
        ForwardOperationPhase.Running,
        ForwardOperationPhase.Cancelling,
        ->
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Forward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        ForwardOperationPhase.Completed ->
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        ForwardOperationPhase.PartialFailure,
        ForwardOperationPhase.Failed,
        ->
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        ForwardOperationPhase.Cancelled ->
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
