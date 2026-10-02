@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField

/** Existing behavior remains editable without migrating the folder. */
@Composable
@Suppress("LongParameterList", "LongMethod") // Stateless property controls remain one visual section.
internal fun LegacyFolderRuleControls(
    keyword: TextFieldState,
    unread: Boolean,
    muted: Boolean,
    groups: Boolean,
    archived: Boolean,
    mentions: Boolean,
    direct: Boolean,
    pinned: Boolean,
    peopleCount: Int,
    onUnread: (Boolean) -> Unit,
    onMuted: (Boolean) -> Unit,
    onGroups: (Boolean) -> Unit,
    onArchived: (Boolean) -> Unit,
    onMentions: (Boolean) -> Unit,
    onDirect: (Boolean) -> Unit,
    onPinned: (Boolean) -> Unit,
    onPeople: () -> Unit,
) {
    WhiteNoiseTextField(
        state = keyword,
        label = {
            Text(stringResource(R.string.chat_folder_keyword_label))
        },
        lineLimits = TextFieldLineLimits.SingleLine,
    )
    SettingsGroup {
        row("people") {
            SettingsLink(
                it,
                stringResource(R.string.chat_folder_people),
                onPeople,
                value = peopleCount.toString(),
            )
        }
        row("unread") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_unread_only),
                unread,
                onUnread,
            )
        }
        row("mentions") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_unread_mentions_only),
                mentions,
                onMentions,
            )
        }
        row("pinned") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_pinned_only),
                pinned,
                onPinned,
            )
        }
        row("groups") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_groups_only),
                groups,
                onGroups,
            )
        }
        row("direct") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_direct_chats_only),
                direct,
                onDirect,
            )
        }
        row("archive") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_archived_only),
                archived,
                onArchived,
            )
        }
        row("muted") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_include_muted),
                muted,
                onMuted,
            )
        }
    }
}
