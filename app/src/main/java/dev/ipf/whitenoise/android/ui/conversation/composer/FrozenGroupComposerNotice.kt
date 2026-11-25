@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/** Composer replacement while the group is frozen for repair, in the error tone. */
@Composable
internal fun FrozenGroupComposerNotice(modifier: Modifier = Modifier) {
    ComposerNotice(
        text = stringResource(R.string.group_unrecoverable_notice),
        modifier = modifier,
        tone = ComposerNoticeTone.Error,
    )
}
