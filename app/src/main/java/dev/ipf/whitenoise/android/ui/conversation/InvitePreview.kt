package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.GroupRosterLoadState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.group.GroupRosterLoadStatus
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

@Composable
internal fun InvitePreviewPlaceholder(inviterName: String?) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Default.Group,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text =
                    inviterName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.invite_preview_with_inviter, it) }
                        ?: stringResource(R.string.invited_to_this_group),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Prototype invitation pane: who invited you, Decline beside Accept, on the low container above the nav bar. */
@Suppress("FunctionNaming")
@Composable
internal fun InvitationActions(
    inviterName: String?,
    mutationInFlight: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val acceptLabel = stringResource(R.string.accept)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(WhiteNoiseSpacing.CompactScreenMargin),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = InvitationActionsMaximumWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
            ) {
                Text(
                    stringResource(
                        R.string.invited_to_chat_by,
                        inviterName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.someone),
                    ),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    WhiteNoiseOutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        enabled = !mutationInFlight,
                    ) {
                        Text(stringResource(R.string.decline), color = MaterialTheme.colorScheme.error)
                    }
                    WhiteNoiseButton(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f).performanceTestTag(PerformanceTestTags.JOIN_INVITE),
                        loading = mutationInFlight,
                        loadingLabel = acceptLabel,
                    ) {
                        Text(acceptLabel)
                    }
                }
            }
        }
    }
}

private val InvitationActionsMaximumWidth = 520.dp

/**
 * Shows progress or the existing localized roster retry when a stale Join was
 * refused and only authoritative membership can choose member vs terminal UI.
 */
@Composable
@Suppress("FunctionNaming")
internal fun InviteAcceptanceResolutionStatus(
    state: GroupRosterLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(vertical = 12.dp),
    ) {
        GroupRosterLoadStatus(
            state = state,
            onRetry = onRetry,
            retryContentColor = MaterialTheme.colorScheme.onSurface,
        )
    }
}
