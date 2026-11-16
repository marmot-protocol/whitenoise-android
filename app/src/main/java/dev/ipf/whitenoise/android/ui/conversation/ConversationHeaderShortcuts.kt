@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HeaderShortcut
import dev.ipf.whitenoise.android.ui.group.ConversationMuteControl

/**
 * The optional header buttons chosen in Gestures settings, in the order the person picked them. Each one calls
 * the same handler as its chat details action; [muteControl] is null when Mute/unmute is not chosen.
 */
@Composable
internal fun RowScope.ConversationHeaderShortcuts(
    shortcuts: List<HeaderShortcut>,
    muteControl: ConversationMuteControl?,
    onSearch: () -> Unit,
) {
    for (shortcut in shortcuts) {
        when (shortcut) {
            HeaderShortcut.Search ->
                IconButton(onClick = onSearch, modifier = Modifier.testTag(CONVERSATION_HEADER_SEARCH_TAG)) {
                    Icon(
                        painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.header_shortcut_search),
                    )
                }
            HeaderShortcut.MuteUnmute ->
                muteControl?.let { control ->
                    IconButton(
                        onClick = control::toggle,
                        enabled = !control.pending,
                        modifier = Modifier.testTag(CONVERSATION_HEADER_MUTE_TAG),
                    ) {
                        Icon(painterResource(control.iconRes), contentDescription = stringResource(control.labelRes))
                    }
                }
        }
    }
}

internal const val CONVERSATION_HEADER_SEARCH_TAG = "conversation.header.shortcut.search"
internal const val CONVERSATION_HEADER_MUTE_TAG = "conversation.header.shortcut.mute"
