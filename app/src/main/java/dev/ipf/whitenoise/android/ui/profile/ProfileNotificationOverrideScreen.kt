@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverride
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.ui.group.VibrationPatternDialog
import dev.ipf.whitenoise.android.ui.group.vibrationPatternLabel
import dev.ipf.whitenoise.android.ui.settings.SettingsChoice
import dev.ipf.whitenoise.android.ui.settings.SettingsExplainer
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsLink
import dev.ipf.whitenoise.android.ui.settings.SettingsList
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/** Bind one profile page to its disposable controller and the Android settings return lifecycle. */
@Composable
internal fun ProfileNotificationOverrideRoot(
    preferences: ProfileNotificationOverridePreferences,
    account: String,
    author: String,
    title: String,
    ownerIsCurrent: () -> Boolean,
    onBack: () -> Unit,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller =
        remember(preferences, account, author) {
            ProfileNotificationOverrideController(
                context,
                preferences,
                account,
                author,
                scope,
                ownerIsCurrent,
                ioDispatcher,
            )
        }
    val state by controller.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    DisposableEffect(controller, lifecycle, title) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) controller.refresh(title)
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller, title) { controller.refresh(title) }
    ProfileNotificationOverrideScreen(
        state,
        onBack,
        controller::selectMode,
        onSystemSettings = { controller.customize(title) },
        onChooseVibration = { controller.chooseVibration(true) },
    )
    if (state.choosingVibration) {
        VibrationPatternDialog(
            state.selection.vibration,
            onDismiss = { controller.chooseVibration(false) },
            onSelect = { controller.customize(title, it, openSettings = false) },
        )
    }
}

/** Collapsed overview copy names the strongest local choice without claiming to know a custom sound's name. */
@Composable
internal fun profileNotificationSummary(selection: ProfileNotificationOverride): String =
    stringResource(
        when (selection.mode) {
            ProfileNotificationMode.DEFAULT -> R.string.profile_notifications_default
            ProfileNotificationMode.MUTED -> R.string.profile_notifications_muted
            ProfileNotificationMode.CUSTOM ->
                if (selection.vibration == ConversationVibrationPattern.SYSTEM_DEFAULT) {
                    R.string.profile_notifications_custom
                } else {
                    R.string.profile_notifications_custom_vibration
                }
        },
    )

/** Pure screen: render the current routing choice and forward explicit customization actions. */
@Suppress("LongMethod") // Four related settings rows form one cohesive presentation surface.
@Composable
internal fun ProfileNotificationOverrideScreen(
    state: ProfileNotificationOverrideState,
    onBack: () -> Unit,
    onMode: (ProfileNotificationMode) -> Unit,
    onSystemSettings: () -> Unit,
    onChooseVibration: () -> Unit,
) {
    SettingsScaffold(stringResource(R.string.notifications), onBack) {
        SettingsList {
            item(key = "scope") { SettingsExplainer(stringResource(R.string.profile_notification_scope_detail)) }
            item(key = "choices") {
                SettingsGroup {
                    row("defaults") { row ->
                        SettingsChoice(
                            context = row,
                            title = stringResource(R.string.profile_notifications_use_defaults),
                            selected = state.selection.mode == ProfileNotificationMode.DEFAULT,
                            onClick = { onMode(ProfileNotificationMode.DEFAULT) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("profile_notifications.defaults"),
                        )
                    }
                    row("mute") { row ->
                        SettingsChoice(
                            context = row,
                            title = stringResource(R.string.profile_notifications_mute),
                            selected = state.selection.mode == ProfileNotificationMode.MUTED,
                            onClick = { onMode(ProfileNotificationMode.MUTED) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("profile_notifications.mute"),
                        )
                    }
                    row("system") { row ->
                        SettingsLink(
                            row,
                            stringResource(R.string.profile_notifications_system),
                            onSystemSettings,
                            value = profileNotificationSummary(state.selection),
                            enabled = !state.busy,
                            modifier = Modifier.testTag("profile_notifications.system"),
                        )
                    }
                    row("vibration") { row ->
                        SettingsLink(
                            row,
                            stringResource(R.string.vibration_pattern),
                            onChooseVibration,
                            value =
                                if (state.effectiveVibration?.overriddenByAndroid == true) {
                                    stringResource(R.string.profile_notifications_android_vibration)
                                } else {
                                    vibrationPatternLabel(state.selection.vibration)
                                },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("profile_notifications.vibration"),
                        )
                    }
                }
            }
            item(key = "precedence") { SettingsExplainer(stringResource(R.string.profile_notification_precedence)) }
            if (state.failed) {
                item(key = "error") {
                    SettingsExplainer(stringResource(R.string.toast_notification_scope_update_failed))
                }
            }
        }
    }
}

/** Realistic custom state for design iteration without Android channel creation. */
@Preview
@Composable
private fun ProfileNotificationOverridePreview() {
    WhiteNoiseTheme {
        ProfileNotificationOverrideScreen(
            ProfileNotificationOverrideState(ProfileNotificationOverride(ProfileNotificationMode.CUSTOM)),
            {},
            {},
            {},
            {},
        )
    }
}
