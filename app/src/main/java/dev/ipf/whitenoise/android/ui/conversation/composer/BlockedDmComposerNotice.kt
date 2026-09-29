@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/** Read-only DM composer replacement with one account-scoped unblock action. */
@Composable
internal fun BlockedDmComposerNotice(
    unblockInFlight: Boolean,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .testTag("conversation.blocked_dm_notice"),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.dm_send_blocked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onUnblock,
                enabled = !unblockInFlight,
                modifier =
                    Modifier
                        .heightIn(min = 48.dp)
                        .testTag("conversation.blocked_dm_unblock")
                        .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(stringResource(R.string.profile_unblock))
            }
        }
    }
}
