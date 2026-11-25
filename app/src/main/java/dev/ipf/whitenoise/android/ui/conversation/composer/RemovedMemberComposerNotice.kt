@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/**
 * Explains the disabled composer for a member who left or was removed, or a device that was removed while its
 * account stays in ([deviceRemoved]). The timeline system row carries the left-vs-removed distinction.
 */
@Composable
internal fun RemovedMemberComposerNotice(
    modifier: Modifier = Modifier,
    deviceRemoved: Boolean = false,
) {
    ComposerNotice(
        text =
            stringResource(
                if (deviceRemoved) {
                    R.string.device_removed_from_group_composer
                } else {
                    R.string.you_are_no_longer_a_member
                },
            ),
        modifier = modifier,
    )
}
