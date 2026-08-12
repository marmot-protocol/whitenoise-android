package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.isForConversation
import dev.ipf.whitenoise.android.ui.common.InlineConfirmationNotice

internal const val CONVERSATION_TRANSIENT_NOTICE_TAG = "conversation-transient-notice"

@Composable
@Suppress("FunctionNaming")
internal fun ConversationTransientNotice(
    notice: TransientNotice?,
    accountRef: String?,
    groupIdHex: String,
    modifier: Modifier = Modifier,
) {
    notice?.takeIf { accountRef != null && it.isForConversation(accountRef, groupIdHex) }?.let {
        InlineConfirmationNotice(
            notice = it,
            modifier = modifier.testTag(CONVERSATION_TRANSIENT_NOTICE_TAG),
        )
    }
}
