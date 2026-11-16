@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HeaderShortcut
import dev.ipf.whitenoise.android.ui.group.ConversationMuteControl

/**
 * The optional header buttons chosen in Chat header settings, in the order the person picked them. Each one
 * calls the same handler as its chat details action; [muteControl] is null when Mute/unmute is not chosen and
 * [readAloudOn] is null when this device has no usable speech engine. [searchModifier] lets the search field
 * grow out of the search button.
 */
@Composable
internal fun RowScope.ConversationHeaderShortcuts(
    shortcuts: List<HeaderShortcut>,
    muteControl: ConversationMuteControl?,
    onSearch: () -> Unit,
    readAloudOn: Boolean? = null,
    onToggleReadAloud: () -> Unit = {},
    searchModifier: Modifier = Modifier,
) {
    for (shortcut in shortcuts) {
        when (shortcut) {
            HeaderShortcut.Search ->
                IconButton(
                    onClick = onSearch,
                    modifier = searchModifier.testTag(CONVERSATION_HEADER_SEARCH_TAG),
                ) {
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
            HeaderShortcut.ReadAloud ->
                readAloudOn?.let { on ->
                    IconToggleButton(
                        checked = on,
                        onCheckedChange = { onToggleReadAloud() },
                        modifier = Modifier.testTag(CONVERSATION_HEADER_READ_ALOUD_TAG),
                    ) {
                        Icon(
                            painterResource(if (on) R.drawable.ic_record_voice_over else R.drawable.ic_voice_over_off),
                            contentDescription = stringResource(R.string.tts_auto_read_title),
                        )
                    }
                }
        }
    }
}

internal const val CONVERSATION_HEADER_SEARCH_TAG = "conversation.header.shortcut.search"
internal const val CONVERSATION_HEADER_MUTE_TAG = "conversation.header.shortcut.mute"
internal const val CONVERSATION_HEADER_READ_ALOUD_TAG = "conversation.header.shortcut.readAloud"
