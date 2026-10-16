package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.ui.common.TopNoticeRegion
import dev.ipf.whitenoise.android.ui.common.rememberTopNoticeSlot

/**
 * The conversation's temporary-banner slot: its own row below the stable app bar and playback strip. Keeping it out
 * of the transcript overlay prevents a banner from covering day separators, loading rows and empty states at any
 * font scale, and keeping it at the top leaves the composer's height, line capacity and position untouched while
 * the transcript absorbs the row (#3312). While this conversation owns the banners it shows its own scoped notices,
 * app-wide confirmations and snackbars, and a notice scoped to any other conversation never renders here.
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
    val slot = rememberTopNoticeSlot()
    Column(modifier.fillMaxSize()) {
        TopNoticeRegion(
            slot = slot,
            notice = notice,
            scope = accountRef?.let { ConversationNoticeDestination(it, groupIdHex) },
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            content = content,
        )
    }
}
