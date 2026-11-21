package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

/** Who and what still notifies while the profile is on: chats, people and the switchable exceptions. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AllowedNotificationsSection(
    state: NotificationProfileEditFormState,
    actions: NotificationProfileEditActions,
) {
    val enabled = !state.busy
    val none = stringResource(R.string.notification_profile_allowed_none)
    SettingsSection(stringResource(R.string.notification_profile_allowed_section))
    SettingsGroup {
        row("chats") { context ->
            SettingsLink(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_allowed_chats),
                value =
                    if (state.allowedChatCount == 0) {
                        none
                    } else {
                        pluralStringResource(
                            R.plurals.chat_folder_chat_count,
                            state.allowedChatCount,
                            state.allowedChatCount,
                        )
                    },
                onClick = actions.onOpenChats,
                modifier = Modifier.testTag("notification-profile.chats"),
                leading = { SettingsLeadingIcon(R.drawable.ic_forum) },
            )
        }
        row("people") { context ->
            SettingsLink(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_allowed_people),
                value =
                    if (state.allowedPeopleCount == 0) {
                        none
                    } else {
                        pluralStringResource(
                            R.plurals.notification_profile_people_count,
                            state.allowedPeopleCount,
                            state.allowedPeopleCount,
                        )
                    },
                onClick = actions.onOpenPeople,
                modifier = Modifier.testTag("notification-profile.people"),
                leading = { SettingsLeadingIcon(R.drawable.ic_person) },
            )
        }
        row("dms") { context ->
            SettingsSwitch(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_all_dms),
                subtitle = stringResource(R.string.notification_profile_all_dms_detail),
                checked = state.allowAllDirectMessages,
                onCheckedChange = actions.onDirectMessagesChange,
                modifier = Modifier.testTag("notification-profile.dms"),
                leading = { SettingsLeadingIcon(R.drawable.ic_chat) },
            )
        }
        row("mentions") { context ->
            SettingsSwitch(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_mentions),
                subtitle = stringResource(R.string.notification_profile_mentions_detail),
                checked = state.allowMentions,
                onCheckedChange = actions.onMentionsChange,
                modifier = Modifier.testTag("notification-profile.mentions"),
                leading = { SettingsLeadingIcon(R.drawable.ic_alternate_email) },
            )
        }
        row("repeated") { context ->
            SettingsSwitch(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_repeated),
                subtitle = stringResource(R.string.notification_profile_repeated_detail),
                checked = state.allowRepeatedMessages,
                onCheckedChange = actions.onRepeatedChange,
                modifier = Modifier.testTag("notification-profile.repeated"),
                leading = { SettingsLeadingIcon(R.drawable.ic_replay) },
            )
        }
        row("group_changes") { context ->
            SettingsSwitch(
                context = context,
                enabled = enabled,
                title = stringResource(R.string.notification_profile_group_changes),
                subtitle = stringResource(R.string.notification_profile_group_changes_detail),
                checked = state.allowGroupChanges,
                onCheckedChange = actions.onGroupChangesChange,
                modifier = Modifier.testTag("notification-profile.group-changes"),
                leading = { SettingsLeadingIcon(R.drawable.ic_group_add) },
            )
        }
    }
    SettingsExplainer(stringResource(R.string.notification_profile_allowed_hint))
    state.accountName?.let { SettingsExplainer(stringResource(R.string.notification_profile_allowed_account_note, it)) }
}
