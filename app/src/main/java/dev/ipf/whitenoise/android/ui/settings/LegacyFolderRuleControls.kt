@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        modifier = Modifier.testTag("folder.keyword"),
        supportingText = { Text(stringResource(R.string.folder_keyword_hint)) },
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
                explanation = stringResource(R.string.explanation_organize_folder_people),
                value = peopleCount.toString(),
            )
        }
        row("unread") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_unread_only),
                unread,
                onUnread,
                explanation = stringResource(R.string.explanation_organize_folder_unread_only),
            )
        }
        row("mentions") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_unread_mentions_only),
                mentions,
                onMentions,
                explanation = stringResource(R.string.explanation_organize_folder_mentions_only),
            )
        }
        row("pinned") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_pinned_only),
                pinned,
                onPinned,
                explanation = stringResource(R.string.explanation_organize_folder_pinned_only),
            )
        }
        row("groups") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_groups_only),
                groups,
                onGroups,
                explanation = stringResource(R.string.explanation_organize_folder_groups_only),
            )
        }
        row("direct") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_direct_chats_only),
                direct,
                onDirect,
                explanation = stringResource(R.string.explanation_organize_folder_direct_only),
            )
        }
        row("archive") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_archived_only),
                archived,
                onArchived,
                explanation = stringResource(R.string.explanation_organize_folder_archived_only),
            )
        }
        row("muted") {
            SettingsSwitch(
                it,
                stringResource(R.string.chat_folder_include_muted),
                muted,
                onMuted,
                explanation = stringResource(R.string.explanation_organize_folder_include_muted),
            )
        }
    }
    SettingsExplainer(stringResource(R.string.folder_rule_hint))
}
