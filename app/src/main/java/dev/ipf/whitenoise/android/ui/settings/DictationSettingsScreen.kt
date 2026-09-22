package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalUriHandler
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
 * Dictation: the recognition provider, when a pause ends a phrase, and — only once a pause can end one — what
 * automatic completion does with the transcript, followed by Android's own voice-input settings. Sending on finish
 * keeps its safety note (D04: provider precedence and the delivery default stay production's).
 */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DictationSettingsScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    isOfflineSpeechToTextInstalled: (Context) -> Boolean = ::offlineSpeechToTextInstalled,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val preferences by appState.conversationDictationPreferences.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf<DictationSetting?>(null) }
    var providerSheetOpen by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }
    var osttOpenFailed by remember { mutableStateOf(false) }
    val osttInstalled =
        remember(context, refreshToken, isOfflineSpeechToTextInstalled) {
            isOfflineSpeechToTextInstalled(context)
        }

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
            if (!osttInstalled) {
                item {
                    SettingsGroup(modifier = Modifier.testTag("dictation.ostt_recommendation.group")) {
                        row("open") { rowContext ->
                            SettingsAction(
                                context = rowContext,
                                title = stringResource(R.string.dictation_ostt_recommendation_title),
                                subtitle = stringResource(R.string.dictation_ostt_recommendation_summary),
                                onClick = {
                                    osttOpenFailed =
                                        runCatching { uriHandler.openUri(OSTT_ZAPSTORE_URL) }.isFailure
                                },
                                leading = {
                                    Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                                },
                            )
                        }
                    }
                }
                if (osttOpenFailed) {
                    item {
                        SettingsCallout(
                            text = stringResource(R.string.dictation_ostt_recommendation_open_failed),
                            modifier = Modifier.testTag("dictation.ostt_recommendation.open_failed"),
                            isError = true,
                        )
                    }
                }
            }
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
                    // Only automatic completion consults this, so it has nothing to say while
                    // Paste and Send are the only things that can end a dictation.
                    if (preferences.finishAfterSilenceMillis != null) {
                        row("result") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.dictation_result_title),
                                onClick = { picker = DictationSetting.Result },
                                value = stringResource(dictationDeliveryLabel(preferences.silenceDeliveryMode)),
                            )
                        }
                    }
                }
            }
            if (preferences.finishAfterSilenceMillis != null &&
                preferences.silenceDeliveryMode == ConversationDictationDeliveryMode.SendOnFinish
            ) {
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
                            selected = preferences.silenceDeliveryMode == mode,
                            subtitle = stringResource(dictationDeliveryDescription(mode)),
                        ) {
                            picker = null
                            appState.conversationDictationPreferences.setSilenceDeliveryMode(mode)
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

/** What automatic completion does with the transcript. */
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

/** Package visibility is declared in the manifest so this is deterministic on Android 11+. */
@Suppress("DEPRECATION")
internal fun offlineSpeechToTextInstalled(context: Context): Boolean =
    try {
        context.packageManager.getApplicationInfo(
            OFFLINE_SPEECH_TO_TEXT_PACKAGE,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

private const val MILLIS_PER_SECOND = 1_000L
internal const val OFFLINE_SPEECH_TO_TEXT_PACKAGE = "app.offlinespeechtotext"
internal const val OSTT_ZAPSTORE_URL =
    "https://zapstore.dev/apps/naddr1qqtkzurs9ehkvenvd9hx2umsv4jkx6r5da6x27r5qyv8wumn8ghj7un9d3shjtn6v9c8xar0wfjjuer9wcpzpys5pkhzxd9dqp4ger8du6p5f6y43tcnzqktjzmwvahq5vumtay4qvzqqqr7pv8t57pf"
