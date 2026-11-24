@file:Suppress("FunctionNaming") // Compose entry points.

package dev.ipf.whitenoise.android.ui.conversation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.HeaderShortcut
import dev.ipf.whitenoise.android.ui.common.SlotIconButton
import dev.ipf.whitenoise.android.ui.common.SlotIconToggleButton
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerShortcutIconSize
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerShortcutPitch
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerShortcutTarget
import dev.ipf.whitenoise.android.ui.group.ConversationMuteControl
import kotlin.math.floor

/**
 * The optional header buttons chosen in Chat header settings, in the order the person picked them. Each one
 * calls the same handler as its chat details action. A null handler hides its button: [muteControl] and
 * [readAloudOn] while the chat's account is not the active one (and [readAloudOn] without a usable speech
 * engine), [onMembers] in a direct message and [onAddMembers] unless this person may add members. Buttons
 * use the composer's compact pitch, so their 48dp touch targets overlap the same way and more fit beside the
 * title. Only the first [capacity] buttons that can show appear, so the chat name always keeps its room; see
 * [headerShortcutCapacity].
 */
@Composable
internal fun RowScope.ConversationHeaderShortcuts(
    shortcuts: List<HeaderShortcut>,
    capacity: Int,
    muteControl: ConversationMuteControl?,
    onSearch: () -> Unit,
    readAloudOn: Boolean? = null,
    onToggleReadAloud: () -> Unit = {},
    onMembers: (() -> Unit)? = null,
    onAddMembers: (() -> Unit)? = null,
) {
    val cell = Modifier.width(ComposerShortcutPitch).height(ComposerShortcutTarget)
    val actions =
        listOfNotNull(
            HeaderAction(HeaderShortcut.Search, R.drawable.ic_search, R.string.header_shortcut_search, onSearch),
            muteControl?.let {
                HeaderAction(HeaderShortcut.MuteUnmute, it.iconRes, it.labelRes, it::toggle, enabled = !it.pending)
            },
            readAloudOn?.let { on ->
                val icon = if (on) R.drawable.ic_record_voice_over else R.drawable.ic_voice_over_off
                HeaderAction(
                    HeaderShortcut.ReadAloud,
                    icon,
                    R.string.tts_auto_read_title,
                    onToggleReadAloud,
                    checked = on,
                )
            },
            onMembers?.let {
                HeaderAction(HeaderShortcut.Members, R.drawable.ic_group, R.string.header_shortcut_members, it)
            },
            onAddMembers?.let {
                HeaderAction(HeaderShortcut.AddMembers, R.drawable.ic_group_add, R.string.add_member, it)
            },
        ).associateBy { it.shortcut }
    for (action in shortcuts.mapNotNull(actions::get).take(capacity.coerceAtLeast(0))) {
        HeaderActionButton(action, cell.testTag(headerShortcutTag(action.shortcut)))
    }
}

/** One header button; [checked] makes it an on/off toggle (read aloud). */
private class HeaderAction(
    val shortcut: HeaderShortcut,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
)

/**
 * One header shortcut in its 36 by 48 dp cell: a slot button, or a slot toggle for an action with a checked
 * state, so the whole cell takes the tap and the highlight stays a circle rather than a capsule.
 */
@Composable
private fun HeaderActionButton(
    action: HeaderAction,
    modifier: Modifier,
) {
    val icon: @Composable () -> Unit = {
        Icon(
            painterResource(action.iconRes),
            contentDescription = stringResource(action.labelRes),
            modifier = Modifier.size(ComposerShortcutIconSize),
        )
    }
    val checked = action.checked
    // Slot buttons: the header's cells are 36 by 48 dp, where an IconButton's circle stretches into a capsule.
    if (checked != null) {
        SlotIconToggleButton(
            checked = checked,
            onCheckedChange = { action.onClick() },
            modifier = modifier,
            content = icon,
        )
    } else {
        SlotIconButton(onClick = action.onClick, enabled = action.enabled, modifier = modifier, content = icon)
    }
}

/**
 * How many header buttons fit in a top bar [barWidth] wide. The chat name keeps at least as much width as
 * the buttons take, and never less than [HeaderMinTitleWidth] scaled by [fontScale] (the title's size
 * relative to 100% text), so a few letters of the name stay readable with large text.
 */
internal fun headerShortcutCapacity(
    barWidth: Dp,
    fontScale: Float,
): Int {
    if (barWidth == Dp.Infinity || barWidth == Dp.Unspecified) return Int.MAX_VALUE
    val titleSpace = (barWidth - HeaderFixedWidth).value
    val pitch = ComposerShortcutPitch.value
    val evenSplit = titleSpace / (2 * pitch)
    val afterMinimum = (titleSpace - HeaderMinTitleWidth.value * fontScale) / pitch
    return floor(minOf(evenSplit, afterMinimum)).toInt().coerceAtLeast(0)
}

// Back button slot with its 4dp start padding (52dp), the 40dp avatar, the 4dp gap before the name and
// the action row's 4dp end padding.
private val HeaderFixedWidth = 100.dp
private val HeaderMinTitleWidth = 72.dp

private fun headerShortcutTag(shortcut: HeaderShortcut): String =
    when (shortcut) {
        HeaderShortcut.Search -> CONVERSATION_HEADER_SEARCH_TAG
        HeaderShortcut.MuteUnmute -> CONVERSATION_HEADER_MUTE_TAG
        HeaderShortcut.ReadAloud -> CONVERSATION_HEADER_READ_ALOUD_TAG
        HeaderShortcut.Members -> CONVERSATION_HEADER_MEMBERS_TAG
        HeaderShortcut.AddMembers -> CONVERSATION_HEADER_ADD_MEMBERS_TAG
    }

internal const val CONVERSATION_HEADER_SEARCH_TAG = "conversation.header.shortcut.search"
internal const val CONVERSATION_HEADER_MUTE_TAG = "conversation.header.shortcut.mute"
internal const val CONVERSATION_HEADER_READ_ALOUD_TAG = "conversation.header.shortcut.readAloud"
internal const val CONVERSATION_HEADER_MEMBERS_TAG = "conversation.header.shortcut.members"
internal const val CONVERSATION_HEADER_ADD_MEMBERS_TAG = "conversation.header.shortcut.addMembers"
