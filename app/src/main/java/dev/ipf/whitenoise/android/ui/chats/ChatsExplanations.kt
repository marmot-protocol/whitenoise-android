package dev.ipf.whitenoise.android.ui.chats

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.ui.common.Explanation

/** Hold-to-explain copy for chat-list and chat-info controls: the title reuses the control's own label. */
@Composable
internal fun chatsExplanation(
    @StringRes title: Int,
    @StringRes body: Int,
): Explanation = Explanation(stringResource(title), stringResource(body))
