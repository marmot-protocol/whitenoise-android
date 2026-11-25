@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Read-only DM composer replacement with one account-scoped unblock action. */
@Composable
internal fun BlockedDmComposerNotice(
    unblockInFlight: Boolean,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ComposerNotice(
        text = stringResource(R.string.dm_send_blocked),
        modifier = modifier.testTag("conversation.blocked_dm_notice"),
    ) {
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
