package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationDeliveryMode
import dev.ipf.whitenoise.android.state.ConversationDictationPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SpeechChoice
import dev.ipf.whitenoise.android.ui.common.SpeechChoiceDialog

/** Which dictation picker is open. */
private enum class DictationSetting { Finish, Result }

/**
 * Dictation: the recognition provider, when a pause ends a phrase, and what happens to the transcript, followed by
 * Android's own voice-input settings. Sending on finish keeps its safety note (D04: provider precedence and the
 * delivery default stay production's).
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DictationSettingsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val preferences by appState.conversationDictationPreferences.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf<DictationSetting?>(null) }
    var providerSheetOpen by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshToken++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(appState, refreshToken) { appState.discoverDictationProviders() }

    SettingsScaffold(title = stringResource(R.string.dictation_settings_title), onBack = onBack) {
        SettingsList {
            item { SettingsExplainer(stringResource(R.string.dictation_settings_explainer)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("dictation.preferences.group")) {
                    row("provider") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.dictation_provider_title),
                            onClick = { providerSheetOpen = true },
                            value =
                                preferences.providerSelection?.let { "${it.appName} — ${it.engineName}" }
                                    ?: stringResource(R.string.dictation_provider_automatic),
                        )
                    }
                    row("finish") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.dictation_finish_title),
                            onClick = { picker = DictationSetting.Finish },
                            value = dictationFinishLabel(preferences.finishAfterSilenceMillis),
                        )
                    }
                    row("result") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.dictation_result_title),
                            onClick = { picker = DictationSetting.Result },
                            value = stringResource(dictationDeliveryLabel(preferences.deliveryMode)),
                        )
                    }
                }
            }
            if (preferences.deliveryMode == ConversationDictationDeliveryMode.SendOnFinish) {
                item { SettingsExplainer(stringResource(R.string.dictation_send_safety_note)) }
            }
            item {
                SettingsGroup(modifier = Modifier.testTag("dictation.system.group")) {
                    row("android_settings") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.speech_android_settings),
                            onClick = { settingsFailed = !openVoiceInputSettings(context) },
                            leading = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) },
                        )
                    }
                }
            }
            if (settingsFailed) {
                item {
                    SettingsCallout(
                        text = stringResource(R.string.speech_settings_failed),
                        modifier = Modifier.testTag("dictation.settings_failed"),
                        isError = true,
                    )
                }
            }
        }
    }

    if (providerSheetOpen) {
        DictationProviderChooser(
            appState,
            onSelected = { providerSheetOpen = false },
            onDismiss = { providerSheetOpen = false },
        )
    }
    when (picker) {
        DictationSetting.Finish ->
            SpeechChoiceDialog(
                title = stringResource(R.string.dictation_finish_title),
                choices =
                    listOf(
                        SpeechChoice(
                            title = stringResource(R.string.dictation_finish_manual),
                            selected = preferences.finishAfterSilenceMillis == null,
                        ) {
                            picker = null
                            appState.conversationDictationPreferences.setFinishAfterSilenceMillis(null)
                        },
                    ) +
                        ConversationDictationPreferences.ALLOWED_SILENCE_MILLIS.sorted().map { millis ->
                            SpeechChoice(
                                title = dictationFinishLabel(millis),
                                selected = preferences.finishAfterSilenceMillis == millis,
                            ) {
                                picker = null
                                appState.conversationDictationPreferences.setFinishAfterSilenceMillis(millis)
                            }
                        },
                onDismiss = { picker = null },
            )
        DictationSetting.Result ->
            SpeechChoiceDialog(
                title = stringResource(R.string.dictation_result_title),
                choices =
                    ConversationDictationDeliveryMode.entries.map { mode ->
                        SpeechChoice(
                            title = stringResource(dictationDeliveryLabel(mode)),
                            selected = preferences.deliveryMode == mode,
                            subtitle = stringResource(dictationDeliveryDescription(mode)),
                        ) {
                            picker = null
                            appState.conversationDictationPreferences.setDeliveryMode(mode)
                        }
                    },
                onDismiss = { picker = null },
            )
        null -> Unit
    }
}

/** "Finish manually", or the silence threshold in whole seconds. */
@Composable
private fun dictationFinishLabel(finishAfterSilenceMillis: Long?): String =
    finishAfterSilenceMillis?.let {
        stringResource(R.string.dictation_finish_after_silence, it / MILLIS_PER_SECOND)
    } ?: stringResource(R.string.dictation_finish_manual)

/** What the transcript does when dictation finishes. */
@androidx.annotation.StringRes
private fun dictationDeliveryLabel(mode: ConversationDictationDeliveryMode): Int =
    when (mode) {
        ConversationDictationDeliveryMode.PasteIntoDraft -> R.string.dictation_result_paste
        ConversationDictationDeliveryMode.SendOnFinish -> R.string.dictation_result_send
    }

/** The consequence of each delivery mode, shown beneath it in the picker. */
@androidx.annotation.StringRes
private fun dictationDeliveryDescription(mode: ConversationDictationDeliveryMode): Int =
    when (mode) {
        ConversationDictationDeliveryMode.PasteIntoDraft -> R.string.dictation_result_paste_description
        ConversationDictationDeliveryMode.SendOnFinish -> R.string.dictation_result_send_description
    }

/** Hands off to Android's voice-input settings, falling back to the settings root. */
private fun openVoiceInputSettings(context: Context): Boolean =
    runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        .isSuccess

private const val MILLIS_PER_SECOND = 1_000L
