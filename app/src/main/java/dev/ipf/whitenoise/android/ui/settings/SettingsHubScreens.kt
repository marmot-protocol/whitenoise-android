package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.EnterKeyBehavior
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quickAccountSwitching
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.profile.ProfileReadiness
import dev.ipf.whitenoise.android.ui.profile.rememberProfileReadiness

/*
 * Intermediate Settings pages. Each is a plain list of existing destinations and controls, so the home
 * stays short while every setting keeps one owner. Item keys follow the `search.<Screen>.<n>` anchors.
 */

/** Account hub: the profile, its keys and the relays that carry it. */
@Suppress("FunctionNaming")
@Composable
internal fun AccountScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    val readiness =
        rememberProfileReadiness(
            appState,
            appState.activeAccount?.accountIdHex,
            appState.profileRevisionForCompose,
            appState::userProfileCached,
            appState::loadUserProfile,
        )
    SettingsScaffold(title = stringResource(R.string.account), onBack = onBack) {
        SettingsList {
            item(key = "search.Account.1") { AccountHubGroup(readiness, onOpenDetail) }
        }
    }
}

/** The Account hub's connected group; the Profile row carries the live readiness summary. */
@Suppress("FunctionNaming")
@Composable
internal fun AccountHubGroup(
    readiness: ProfileReadiness,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    SettingsGroup(modifier = Modifier.testTag("settings.account.group")) {
        row("profile") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.profile),
                explanation = stringResource(R.string.explanation_organize_hub_profile),
                subtitle = stringResource(readiness.summary),
                onClick = { onOpenDetail(SettingsDetail.Profile) },
            )
        }
        row("profile_keys") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.settings_profile_keys),
                explanation = stringResource(R.string.explanation_organize_hub_profile_keys),
                onClick = { onOpenDetail(SettingsDetail.AccountKeys) },
            )
        }
        row("relays") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.relays),
                explanation = stringResource(R.string.explanation_settings_relays),
                onClick = { onOpenDetail(SettingsDetail.Relays) },
            )
        }
    }
}

/** Chats hub: folders, gestures and the composer, with the Enter-key and account-switching preferences inline. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun ChatsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    var enterOpen by rememberSaveable { mutableStateOf(false) }
    val enterLabels = EnterKeyBehavior.entries.associateWith { stringResource(it.labelRes) }
    if (enterOpen) {
        ChoiceDialog(
            title = stringResource(R.string.enter_key_behavior_title),
            values = EnterKeyBehavior.entries,
            selected = appState.enterKeyBehavior,
            label = enterLabels::getValue,
            supportingText = stringResource(R.string.appearance_enter_help),
            onDismiss = { enterOpen = false },
            onSelect = {
                enterOpen = false
                appState.updateEnterKeyBehavior(it)
            },
        )
    }
    SettingsScaffold(title = stringResource(R.string.chats), onBack = onBack) {
        SettingsList {
            item(key = "search.Chats.1") {
                SettingsGroup(modifier = Modifier.testTag("settings.chats.group")) {
                    row("chat_folders") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_folders_title),
                            explanation = stringResource(R.string.explanation_organize_hub_chat_folders),
                            onClick = { onOpenDetail(SettingsDetail.ChatFolders) },
                        )
                    }
                    row("gestures") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.gestures_title),
                            explanation = stringResource(R.string.explanation_organize_hub_gestures),
                            onClick = { onOpenDetail(SettingsDetail.Gestures) },
                        )
                    }
                    row("composer") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.composer_settings),
                            explanation = stringResource(R.string.explanation_organize_hub_composer),
                            subtitle = stringResource(R.string.composer_settings_detail),
                            onClick = { onOpenDetail(SettingsDetail.Composer) },
                        )
                    }
                    row("enter_behavior") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.enter_key_behavior_title),
                            explanation = stringResource(R.string.explanation_organize_hub_enter_behavior),
                            value = enterLabels.getValue(appState.enterKeyBehavior),
                            onClick = { enterOpen = true },
                        )
                    }
                    row("custom_emoji") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.custom_emoji),
                            explanation = stringResource(R.string.explanation_organize_hub_custom_emoji),
                            modifier = Modifier.testTag("appearance.custom_emoji.group"),
                            onClick = { onOpenDetail(SettingsDetail.CustomEmoji) },
                        )
                    }
                    row("quick_account_switching") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.quick_account_switching),
                            explanation = stringResource(R.string.explanation_organize_hub_quick_switching),
                            checked = appState.quickAccountSwitching,
                            subtitle = stringResource(R.string.quick_account_switching_detail),
                            modifier = Modifier.testTag("appearance.quick_account_switching"),
                            onCheckedChange = appState::updateQuickAccountSwitching,
                        )
                    }
                }
            }
        }
    }
}

/** Voice & language hub: the app language and the spoken, dictated and translated text features. */
@Suppress("FunctionNaming")
@Composable
internal fun VoiceLanguageScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    val language = languageOptions.firstOrNull { it.tag == appState.languageTag } ?: languageOptions.first()
    SettingsScaffold(title = stringResource(R.string.settings_voice_language), onBack = onBack) {
        SettingsList {
            item(key = "search.VoiceLanguage.1") {
                SettingsGroup(modifier = Modifier.testTag("settings.voice_language.group")) {
                    row("language") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.language),
                            explanation = stringResource(R.string.explanation_organize_hub_language),
                            value = stringResource(language.labelRes),
                            modifier = Modifier.testTag("appearance.language.group"),
                            onClick = { onOpenDetail(SettingsDetail.Language) },
                        )
                    }
                    row("read_aloud") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_read_aloud),
                            explanation = stringResource(R.string.explanation_organize_hub_read_aloud),
                            onClick = { onOpenDetail(SettingsDetail.TextToSpeech) },
                        )
                    }
                    row("dictation") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.dictation_settings_title),
                            explanation = stringResource(R.string.explanation_organize_hub_dictation),
                            onClick = { onOpenDetail(SettingsDetail.Dictation) },
                        )
                    }
                    row("translation") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.translation_title),
                            explanation = stringResource(R.string.explanation_organize_hub_translation),
                            onClick = { onOpenDetail(SettingsDetail.Translation) },
                        )
                    }
                }
            }
        }
    }
}

/** Help & support hub: guides, the support chat, donations and the developer tools. */
@Suppress("FunctionNaming")
@Composable
internal fun HelpSupportScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_help_support), onBack = onBack) {
        SettingsList {
            item(key = "search.HelpSupport.1") {
                SettingsGroup(modifier = Modifier.testTag("settings.help_support.group")) {
                    row("help") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.help),
                            explanation = stringResource(R.string.explanation_organize_hub_help),
                            onClick = { onOpenDetail(SettingsDetail.Help) },
                        )
                    }
                    if (appState.activeAccount != null) {
                        row("chat_with_support") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.chat_with_support),
                                explanation = stringResource(R.string.explanation_organize_hub_support),
                                onClick = { onOpenDetail(SettingsDetail.Support) },
                            )
                        }
                    }
                    row("donate") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_donate),
                            explanation = stringResource(R.string.explanation_organize_hub_donate),
                            onClick = { onOpenDetail(SettingsDetail.Donate) },
                        )
                    }
                    row("developer_tools") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_developer_tools),
                            explanation = stringResource(R.string.explanation_organize_hub_developer),
                            onClick = { onOpenDetail(SettingsDetail.Developer) },
                        )
                    }
                }
            }
        }
    }
}
