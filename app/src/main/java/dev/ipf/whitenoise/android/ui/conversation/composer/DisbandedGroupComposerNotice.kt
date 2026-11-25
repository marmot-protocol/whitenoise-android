@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/**
 * Composer replacement while a disband converges or after it lands. Unlike
 * the frozen-group notice this is not an error state — the group ended by
 * design — so it reads in the neutral variant color.
 */
@Composable
internal fun DisbandedGroupComposerNotice(
    disbanded: Boolean,
    modifier: Modifier = Modifier,
) {
    ComposerNotice(
        text =
            stringResource(
                if (disbanded) {
                    R.string.conversation_disbanded_notice
                } else {
                    R.string.conversation_disbanding_notice
                },
            ),
        modifier = modifier,
    )
}
