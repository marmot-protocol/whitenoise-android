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
import dev.ipf.whitenoise.android.state.SignInAlertTier
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.quickAccountSwitching
import dev.ipf.whitenoise.android.state.updateQuickAccountSwitching
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.devices.DevicesRowTrailing
import dev.ipf.whitenoise.android.ui.devices.LinkedDevicesScreen
import dev.ipf.whitenoise.android.ui.devices.SignInDevicesRequestEffect
import dev.ipf.whitenoise.android.ui.devices.devicesRowTier
import dev.ipf.whitenoise.android.ui.navigation.SettingsDetail
import dev.ipf.whitenoise.android.ui.profile.ProfileReadiness
import dev.ipf.whitenoise.android.ui.profile.rememberProfileReadiness

/*
 * Intermediate Settings pages. Each is a plain list of existing destinations and controls, so the home
 * stays short while every setting keeps one owner. Item keys follow the `search.<Screen>.<n>` anchors.
 */

/**
 * Account hub: the profile, its keys, the relays that carry it and the account's device roster, which is its own
 * Settings page that returns here on Back (#4095).
 */
@Suppress("FunctionNaming")
@Composable
internal fun AccountScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    // A sign-in notice, alert or notification for this account opens its devices directly.
    SignInDevicesRequestEffect(appState) { onOpenDetail(SettingsDetail.LinkedDevices) }
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
            item(key = "search.Account.1") {
                AccountHubGroup(
                    readiness = readiness,
                    onOpenLinkedDevices = { onOpenDetail(SettingsDetail.LinkedDevices) },
                    onOpenDetail = onOpenDetail,
                    devicesTier = appState.devicesRowTier(),
                )
            }
        }
    }
}

/**
 * The account's linked devices as a Settings page. A sign-in request that arrives while it is open is taken here, as
 * it was while the roster opened in place, so it never reopens the page after Back.
 */
@Suppress("FunctionNaming")
@Composable
internal fun LinkedDevicesRoute(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    SignInDevicesRequestEffect(appState) {}
    LinkedDevicesScreen(appState, onBack = onBack)
}

/**
 * The Account hub's connected group; the Profile row carries the live readiness summary. The Devices row is
 * present only when [onOpenLinkedDevices] is given, with a badge for a sign-in to review or an open security warning
 * per [devicesTier].
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun AccountHubGroup(
    readiness: ProfileReadiness,
    onOpenLinkedDevices: (() -> Unit)? = null,
    devicesTier: SignInAlertTier = SignInAlertTier.Passive,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    SettingsGroup(
        modifier = Modifier.testTag("settings.account.group"),
        destination = SettingsDetail.Account,
        surface = "account-controls",
    ) {
        row("profile") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.profile),
                explanation = stringResource(R.string.explanation_organize_hub_profile),
                subtitle = stringResource(readiness.summary),
                routeKey = SettingsDetail.Profile,
                onClick = { onOpenDetail(SettingsDetail.Profile) },
                leading = { SettingsLeadingIcon(R.drawable.ic_person) },
            )
        }
        row("profile_keys") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.settings_profile_keys),
                routeKey = SettingsDetail.AccountKeys,
                explanation = stringResource(R.string.explanation_organize_hub_profile_keys),
                onClick = { onOpenDetail(SettingsDetail.AccountKeys) },
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_key) },
            )
        }
        row("relays") { context ->
            SettingsLink(
                context = context,
                title = stringResource(R.string.relays),
                routeKey = SettingsDetail.Relays,
                explanation = stringResource(R.string.explanation_settings_relays),
                onClick = { onOpenDetail(SettingsDetail.Relays) },
                leading = { SettingsLeadingIcon(R.drawable.ic_settings_cell_tower) },
            )
        }
        if (onOpenLinkedDevices != null) {
            row("linked_devices") { context ->
                SettingsLink(
                    context = context,
                    title = stringResource(R.string.linked_devices),
                    routeKey = SettingsDetail.LinkedDevices,
                    explanation = stringResource(R.string.explanation_linked_devices_hub),
                    onClick = onOpenLinkedDevices,
                    modifier = Modifier.testTag("settings.account.linked_devices"),
                    trailing = { DevicesRowTrailing(devicesTier) },
                    leading = { SettingsLeadingIcon(R.drawable.ic_devices) },
                )
            }
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
                SettingsGroup(
                    modifier = Modifier.testTag("settings.chats.group"),
                    destination = SettingsDetail.Chats,
                    surface = "chats-controls",
                ) {
                    row("chat_folders") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.chat_folders_title),
                            routeKey = SettingsDetail.ChatFolders,
                            explanation = stringResource(R.string.explanation_organize_hub_chat_folders),
                            onClick = { onOpenDetail(SettingsDetail.ChatFolders) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_folder) },
                        )
                    }
                    row("gestures") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.gestures_title),
                            routeKey = SettingsDetail.Gestures,
                            explanation = stringResource(R.string.explanation_organize_hub_gestures),
                            onClick = { onOpenDetail(SettingsDetail.Gestures) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_swipe) },
                        )
                    }
                    row("composer") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.composer_settings),
                            explanation = stringResource(R.string.explanation_organize_hub_composer),
                            subtitle = stringResource(R.string.composer_settings_detail),
                            routeKey = SettingsDetail.Composer,
                            onClick = { onOpenDetail(SettingsDetail.Composer) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_edit_note) },
                        )
                    }
                    row("chat_header") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.header_shortcuts_title),
                            explanation = stringResource(R.string.explanation_organize_hub_chat_header),
                            onClick = { onOpenDetail(SettingsDetail.ChatHeader) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_tune) },
                        )
                    }
                    row("enter_behavior") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.enter_key_behavior_title),
                            explanation = stringResource(R.string.explanation_organize_hub_enter_behavior),
                            value = enterLabels.getValue(appState.enterKeyBehavior),
                            onClick = { enterOpen = true },
                            leading = { SettingsLeadingIcon(R.drawable.ic_keyboard_return) },
                        )
                    }
                    row("custom_emoji") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.custom_emoji),
                            explanation = stringResource(R.string.explanation_organize_hub_custom_emoji),
                            modifier = Modifier.testTag("appearance.custom_emoji.group"),
                            routeKey = SettingsDetail.CustomEmoji,
                            onClick = { onOpenDetail(SettingsDetail.CustomEmoji) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_mood) },
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
                            leading = { SettingsLeadingIcon(R.drawable.ic_switch_account) },
                        )
                    }
                }
            }
        }
    }
}

/** Voice & language hub: the app language and the spoken, dictated and translated text features. */
@Suppress("FunctionNaming", "LongMethod") // Keep the declared hub row order together.
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
                SettingsGroup(
                    modifier = Modifier.testTag("settings.voice_language.group"),
                    destination = SettingsDetail.VoiceLanguage,
                    surface = "voice-language-controls",
                ) {
                    row("language") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.language),
                            explanation = stringResource(R.string.explanation_organize_hub_language),
                            value = stringResource(language.labelRes),
                            modifier = Modifier.testTag("appearance.language.group"),
                            routeKey = SettingsDetail.Language,
                            onClick = { onOpenDetail(SettingsDetail.Language) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_language) },
                        )
                    }
                    row("voice_notes") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_voice_notes),
                            routeKey = SettingsDetail.VoiceNotes,
                            onClick = { onOpenDetail(SettingsDetail.VoiceNotes) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_mic) },
                        )
                    }
                    row("read_aloud") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_read_aloud),
                            routeKey = SettingsDetail.TextToSpeech,
                            explanation = stringResource(R.string.explanation_organize_hub_read_aloud),
                            onClick = { onOpenDetail(SettingsDetail.TextToSpeech) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_record_voice_over) },
                        )
                    }
                    row("dictation") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.dictation_settings_title),
                            routeKey = SettingsDetail.Dictation,
                            explanation = stringResource(R.string.explanation_organize_hub_dictation),
                            onClick = { onOpenDetail(SettingsDetail.Dictation) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_mic) },
                        )
                    }
                    row("translation") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.translation_title),
                            routeKey = SettingsDetail.Translation,
                            explanation = stringResource(R.string.explanation_organize_hub_translation),
                            onClick = { onOpenDetail(SettingsDetail.Translation) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_translate) },
                        )
                    }
                }
            }
        }
    }
}

/** Help & support hub: first-run help, the support chat, donations and the developer tools. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun HelpSupportScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDetail: (SettingsDetail?) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_help_support), onBack = onBack) {
        SettingsList {
            item(key = "search.HelpSupport.1") {
                SettingsGroup(
                    modifier = Modifier.testTag("settings.help_support.group"),
                    destination = SettingsDetail.HelpSupport,
                    surface = "help-support-controls",
                ) {
                    row("learn_white_noise") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.learn_white_noise),
                            routeKey = SettingsDetail.LearnWhiteNoise,
                            leading = { SettingsLeadingIcon(R.drawable.ic_help) },
                            onClick = { onOpenDetail(SettingsDetail.LearnWhiteNoise) },
                        )
                    }
                    row("help") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.help),
                            routeKey = SettingsDetail.Help,
                            explanation = stringResource(R.string.explanation_organize_hub_help),
                            onClick = { onOpenDetail(SettingsDetail.Help) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_help) },
                        )
                    }
                    if (appState.activeAccount != null) {
                        row("chat_with_support") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.chat_with_support),
                                routeKey = SettingsDetail.Support,
                                explanation = stringResource(R.string.explanation_organize_hub_support),
                                onClick = { onOpenDetail(SettingsDetail.Support) },
                                leading = { SettingsLeadingIcon(R.drawable.ic_support_agent) },
                            )
                        }
                    }
                    row("donate") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_donate),
                            routeKey = SettingsDetail.Donate,
                            explanation = stringResource(R.string.explanation_organize_hub_donate),
                            onClick = { onOpenDetail(SettingsDetail.Donate) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_settings_favorite_border) },
                        )
                    }
                    row("developer_tools") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.settings_developer_tools),
                            routeKey = SettingsDetail.Developer,
                            explanation = stringResource(R.string.explanation_organize_hub_developer),
                            onClick = { onOpenDetail(SettingsDetail.Developer) },
                            leading = { SettingsLeadingIcon(R.drawable.ic_settings_handyman) },
                        )
                    }
                }
            }
        }
    }
}
