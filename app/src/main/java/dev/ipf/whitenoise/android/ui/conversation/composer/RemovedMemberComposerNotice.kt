@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.BottomPanelSurface

/**
 * Explains the disabled composer. [deviceRemoved] says only this device left the group while the account
 * stays in through another device, so it must not read as the account's removal.
 */
@Composable
internal fun RemovedMemberComposerNotice(
    modifier: Modifier = Modifier,
    deviceRemoved: Boolean = false,
) {
    BottomPanelSurface(modifier = modifier.navigationBarsPadding()) {
        Text(
            // Explains the disabled composer for a member who left or was removed, or a device that was
            // removed while its account stays in. The timeline system row carries the left-vs-removed distinction.
            text =
                stringResource(
                    if (deviceRemoved) {
                        R.string.device_removed_from_group_composer
                    } else {
                        R.string.you_are_no_longer_a_member
                    },
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
