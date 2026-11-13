package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppLockDelay
import dev.ipf.whitenoise.android.state.ProductObservation
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.recordProductObservation
import dev.ipf.whitenoise.android.state.setDefaultDisappearingMessagesSeconds
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.group.DisappearingMessagesPickerDialog
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel

/**
 * Privacy & Security: device protection switches, the auto-lock choice once device authentication is on, and the
 * Diagnostics & Improvements link with a truthful one-word summary. Screen security stays one combined control
 * because the app owns one secure-window flag (D05); there is no erase-everything backend, so that section is absent.
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DevicePrivacyScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenBlockedUsers: () -> Unit = {},
    credentialAvailableOverride: Boolean? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { appState.recordProductObservation(ProductObservation.PRIVACY) }
    LaunchedEffect(appState.runtimeGeneration) {
        appState.refreshAppLockCredentialAvailability()
        appState.refreshSecurityPrivacySettings()
    }
    val secure = credentialAvailableOverride ?: appState.appLockCredentialAvailable
    val authenticationEnabled = secure && appState.requireAppUnlock
    var autoLockPicker by rememberSaveable { mutableStateOf(false) }
    var defaultDisappearingPicker by rememberSaveable(appState.activeAccountRef) { mutableStateOf(false) }
    val defaultDisappearingDurations by appState.defaultDisappearingMessagesPreferences.durations.collectAsState()
    val activeAccountRef = appState.activeAccountRef
    val defaultDisappearingSeconds =
        appState.defaultDisappearingMessagesPreferences.durationFor(
            accountRef = activeAccountRef,
            snapshot = defaultDisappearingDurations,
        )
    LaunchedEffect(authenticationEnabled) {
        if (!authenticationEnabled) autoLockPicker = false
    }
    val delayLabels = AppLockDelay.entries.associateWith { stringResource(it.labelRes) }
    val summary =
        diagnosticsSummaryRes(
            usageGranted = appState.diagnostics.granted,
            auditLogsEnabled = appState.auditLogSettings?.enabled == true,
        )
    SettingsScaffold(title = stringResource(R.string.settings_privacy_security), onBack = onBack) {
        SettingsList {
            item(key = "search.DevicePrivacy.1") { SettingsSection(stringResource(R.string.device_protection)) }
            item(key = "search.DevicePrivacy.2") {
                SettingsGroup(modifier = Modifier.testTag("privacy.device_protection.group")) {
                    row("screen_security") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.allow_chat_screenshots),
                            checked = !appState.allowChatScreenshotsInChats,
                            onCheckedChange = { appState.updateAllowChatScreenshotsInChats(!it) },
                            subtitle = stringResource(R.string.allow_chat_screenshots_subtitle),
                            leading = { SettingsLeadingIcon(R.drawable.ic_screenshot_monitor) },
                        )
                    }
                    row("incognito_keyboard") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.force_incognito_keyboard),
                            checked = appState.forceIncognitoKeyboard,
                            onCheckedChange = { appState.updateForceIncognitoKeyboard(it) },
                            subtitle = stringResource(R.string.incognito_keyboard_detail),
                            leading = { SettingsLeadingIcon(R.drawable.ic_keyboard) },
                        )
                    }
                    row("device_authentication") { rowContext ->
                        SettingsSwitch(
                            context = rowContext,
                            title = stringResource(R.string.require_device_authentication),
                            checked = authenticationEnabled,
                            modifier = Modifier.testTag("privacy.device_authentication"),
                            onCheckedChange = { appState.updateRequireAppUnlock(it) },
                            subtitle =
                                stringResource(
                                    if (secure) R.string.app_lock_enabled_detail else R.string.app_lock_setup_detail,
                                ),
                            enabled = secure,
                            leading = { SettingsLeadingIcon(R.drawable.ic_fingerprint) },
                        )
                    }
                    if (!secure) {
                        row("security_settings") { rowContext ->
                            SettingsAction(
                                context = rowContext,
                                title = stringResource(R.string.open_android_security_settings),
                                onClick = { openSecuritySettings(context) },
                                leading = { SettingsLeadingIcon(R.drawable.ic_open_in_new) },
                            )
                        }
                    }
                    if (authenticationEnabled) {
                        row("auto_lock") { rowContext ->
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(R.string.auto_lock),
                                onClick = { autoLockPicker = true },
                                value = delayLabels.getValue(appState.appLockDelay),
                                leading = { SettingsLeadingIcon(R.drawable.ic_lock_clock) },
                            )
                        }
                    }
                }
            }
            item(key = "search.DevicePrivacy.3") { SettingsSection(stringResource(R.string.blocked_users)) }
            item(key = "search.DevicePrivacy.4") {
                SettingsGroup(modifier = Modifier.testTag("privacy.blocked_users.group")) {
                    row("blocked_users") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.blocked_users),
                            onClick = onOpenBlockedUsers,
                            value =
                                appState.runtimeMirrors.blocks.users.size
                                    .toString(),
                            leading = { SettingsLeadingIcon(R.drawable.ic_block) },
                        )
                    }
                }
            }
            item(key = "search.DevicePrivacy.5") { SettingsSection(stringResource(R.string.message_privacy)) }
            item(key = "search.DevicePrivacy.6") {
                SettingsGroup(modifier = Modifier.testTag("privacy.default_disappearing.group")) {
                    row("default_disappearing_messages") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.default_disappearing_messages),
                            onClick = { defaultDisappearingPicker = true },
                            value = disappearingMessagesLabel(defaultDisappearingSeconds),
                            subtitle = stringResource(R.string.default_disappearing_messages_detail),
                            enabled = activeAccountRef != null,
                            leading = { SettingsLeadingIcon(R.drawable.ic_timer) },
                        )
                    }
                }
            }
            item(key = "search.DevicePrivacy.7") { SettingsSection(stringResource(R.string.diagnostics)) }
            item(key = "search.DevicePrivacy.8") {
                SettingsGroup(modifier = Modifier.testTag("privacy.diagnostics.group")) {
                    row("diagnostics") { rowContext ->
                        SettingsLink(
                            context = rowContext,
                            title = stringResource(R.string.diagnostics_improvements),
                            onClick = onOpenDiagnostics,
                            value = stringResource(summary),
                            leading = { SettingsLeadingIcon(R.drawable.ic_monitoring) },
                        )
                    }
                }
            }
            item(key = "search.DevicePrivacy.9") {
                SettingsExplainer(stringResource(R.string.diagnostics_controls_detail))
            }
        }
    }
    if (autoLockPicker) {
        ChoiceDialog(
            title = stringResource(R.string.auto_lock),
            values = AppLockDelay.entries,
            selected = appState.appLockDelay,
            label = delayLabels::getValue,
            onDismiss = { autoLockPicker = false },
            onSelect = {
                appState.updateAppLockDelay(it)
                autoLockPicker = false
            },
        )
    }
    if (defaultDisappearingPicker) {
        DisappearingMessagesPickerDialog(
            currentSecs = defaultDisappearingSeconds,
            explainer = stringResource(R.string.default_disappearing_messages_picker_detail),
            onDismiss = { defaultDisappearingPicker = false },
            onPick = { seconds ->
                if (
                    appState.setDefaultDisappearingMessagesSeconds(
                        seconds = seconds,
                        accountRef = activeAccountRef,
                    )
                ) {
                    defaultDisappearingPicker = false
                }
            },
        )
    }
}

/** One word for the state of both sharing controls: On, Usage, Logs or Off. */
internal fun diagnosticsSummaryRes(
    usageGranted: Boolean,
    auditLogsEnabled: Boolean,
): Int =
    when {
        usageGranted && auditLogsEnabled -> R.string.diagnostics_summary_on
        usageGranted -> R.string.diagnostics_summary_usage
        auditLogsEnabled -> R.string.diagnostics_summary_logs
        else -> R.string.usage_diagnostics_off
    }

/** Hands off to Android's security settings so the user can set a screen lock. */
private fun openSecuritySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
}
