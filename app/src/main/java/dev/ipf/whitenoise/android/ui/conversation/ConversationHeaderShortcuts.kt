@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerShortcutIconSize
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerShortcutPitch
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerShortcutTarget
import dev.ipf.whitenoise.android.ui.group.ConversationMuteControl

/**
 * The optional header buttons chosen in Chat header settings, in the order the person picked them. Each one
 * calls the same handler as its chat details action. A null handler hides its button: [muteControl] and
 * [readAloudOn] while the chat's account is not the active one (and [readAloudOn] without a usable speech
 * engine), [onTimer] unless this person may change the timer now, [onMembers] in a direct message and
 * [onAddMembers] unless this person may add members. Buttons use the composer's compact pitch, so their
 * 48dp touch targets overlap the same way and more fit beside the title.
 */
@Composable
internal fun RowScope.ConversationHeaderShortcuts(
    shortcuts: List<HeaderShortcut>,
    muteControl: ConversationMuteControl?,
    onSearch: () -> Unit,
    readAloudOn: Boolean? = null,
    onToggleReadAloud: () -> Unit = {},
    onTimer: (() -> Unit)? = null,
    onMembers: (() -> Unit)? = null,
    onAddMembers: (() -> Unit)? = null,
) {
    val cell = Modifier.width(ComposerShortcutPitch).height(ComposerShortcutTarget)
    for (shortcut in shortcuts) {
        when (shortcut) {
            HeaderShortcut.Search ->
                HeaderShortcutButton(
                    R.drawable.ic_search,
                    stringResource(R.string.header_shortcut_search),
                    onSearch,
                    cell.testTag(CONVERSATION_HEADER_SEARCH_TAG),
                )
            HeaderShortcut.MuteUnmute ->
                muteControl?.let { control ->
                    HeaderShortcutButton(
                        control.iconRes,
                        stringResource(control.labelRes),
                        control::toggle,
                        cell.testTag(CONVERSATION_HEADER_MUTE_TAG),
                        enabled = !control.pending,
                    )
                }
            HeaderShortcut.ReadAloud ->
                readAloudOn?.let { on ->
                    IconToggleButton(
                        checked = on,
                        onCheckedChange = { onToggleReadAloud() },
                        modifier = cell.testTag(CONVERSATION_HEADER_READ_ALOUD_TAG),
                    ) {
                        Icon(
                            painterResource(if (on) R.drawable.ic_record_voice_over else R.drawable.ic_voice_over_off),
                            contentDescription = stringResource(R.string.tts_auto_read_title),
                            modifier = Modifier.size(ComposerShortcutIconSize),
                        )
                    }
                }
            HeaderShortcut.DisappearingTimer ->
                onTimer?.let {
                    HeaderShortcutButton(
                        R.drawable.ic_timer,
                        stringResource(R.string.conversation_header_timer_change),
                        it,
                        cell.testTag(CONVERSATION_HEADER_TIMER_TAG),
                    )
                }
            HeaderShortcut.Members ->
                onMembers?.let {
                    HeaderShortcutButton(
                        R.drawable.ic_group,
                        stringResource(R.string.header_shortcut_members),
                        it,
                        cell.testTag(CONVERSATION_HEADER_MEMBERS_TAG),
                    )
                }
            HeaderShortcut.AddMembers ->
                onAddMembers?.let {
                    HeaderShortcutButton(
                        R.drawable.ic_group_add,
                        stringResource(R.string.add_member),
                        it,
                        cell.testTag(CONVERSATION_HEADER_ADD_MEMBERS_TAG),
                    )
                }
        }
    }
}

@Composable
private fun HeaderShortcutButton(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(painterResource(iconRes), contentDescription = label, modifier = Modifier.size(ComposerShortcutIconSize))
    }
}

internal const val CONVERSATION_HEADER_SEARCH_TAG = "conversation.header.shortcut.search"
internal const val CONVERSATION_HEADER_MUTE_TAG = "conversation.header.shortcut.mute"
internal const val CONVERSATION_HEADER_READ_ALOUD_TAG = "conversation.header.shortcut.readAloud"
internal const val CONVERSATION_HEADER_TIMER_TAG = "conversation.header.shortcut.timer"
internal const val CONVERSATION_HEADER_MEMBERS_TAG = "conversation.header.shortcut.members"
internal const val CONVERSATION_HEADER_ADD_MEMBERS_TAG = "conversation.header.shortcut.addMembers"
