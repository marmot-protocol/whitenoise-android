package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Gives a scoped notice its own row below the stable app bar. Keeping it out of
 * the transcript overlay prevents the notice from covering day separators,
 * loading rows, and empty states at any font scale.
 */
@Composable
@Suppress("FunctionNaming")
internal fun ConversationTransientNoticeLayout(
    notice: TransientNotice?,
    accountRef: String?,
    groupIdHex: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        ConversationTransientNotice(
            notice = notice,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            content = content,
        )
    }
}
