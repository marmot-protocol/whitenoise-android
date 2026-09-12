package dev.ipf.whitenoise.android.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.TtsTrustWarningDialog
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceOption
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceUnavailableReason
import dev.ipf.whitenoise.android.audio.tts.requiresTtsTrustWarning
import dev.ipf.whitenoise.android.audio.tts.shouldReportNoTtsEngine
import dev.ipf.whitenoise.android.state.TtsMediaMixVolume
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.resolvedTtsEnginePackage
import dev.ipf.whitenoise.android.state.selectTtsVoice
import dev.ipf.whitenoise.android.state.setTtsMediaMixEnabled
import dev.ipf.whitenoise.android.state.setTtsMediaMixVolume
import dev.ipf.whitenoise.android.state.ttsEngineChoice
import dev.ipf.whitenoise.android.ui.common.SpeechChoice
import dev.ipf.whitenoise.android.ui.common.SpeechChoiceDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseTextField
import dev.ipf.whitenoise.android.ui.group.TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG
import java.util.Locale

/** Which speech picker is open. */
private enum class SpeechSetting { Engine, Voice, Rate, Volume }

/**
 * Read Aloud: engine, voice and rate first, then the discovery status as a callout, then the auto-read and
 * speak-over-media preferences, then Refresh and Android's own speech settings. Every option list is a dialog
 * whose rows carry their own availability, because a listed voice can still be unselectable (M063, M064).
 */
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun TextToSpeechScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val rateOverride by appState.ttsRatePreferences.rateOverride.collectAsState()
    val mediaMix by appState.ttsMediaMixPreferences.state.collectAsState()
    val ttsAutoReadPrefs by appState.ttsAutoReadPreferences.state.collectAsState()
    val engineChoice = appState.ttsEngineChoice()
    val voiceResolution = appState.ttsVoiceResolution
    val locale = LocalConfiguration.current.locales[0]
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var picker by rememberSaveable { mutableStateOf<SpeechSetting?>(null) }
    var customRateOpen by rememberSaveable { mutableStateOf(false) }
    var pendingEnginePackage by remember { mutableStateOf<String?>(null) }
    var settingsFailed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshToken++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refreshToken) { appState.refreshTtsAvailability() }

    val resolvedPackage = appState.resolvedTtsEnginePackage()
    val showEngineChooser = engineChoice.showEngineChooser || engineChoice.engines.size > 1
    val engineLabel = engineChoice.engines.firstOrNull { it.packageName == resolvedPackage }?.label
    val selectedVoice = resolvedPackage?.let(appState.ttsVoicePreferences::selectedVoice)
    val effectiveVoiceLabel =
        voiceResolution.options.firstOrNull { it.key == voiceResolution.effectiveKey }?.label
            ?: voiceResolution.effectiveKey?.voiceName
    val status = ttsStatusRes(appState, voiceResolution.effectiveKey != null)
    // A saved voice that is unavailable means the engine speaks with a different one; say so.
    val usingFallbackVoice = voiceResolution.requestedKey != null && !voiceResolution.isUsingRequestedVoice

    SettingsScaffold(title = stringResource(R.string.settings_read_aloud), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(modifier = Modifier.testTag("speech.engine.group")) {
                    if (showEngineChooser && engineChoice.engines.isNotEmpty()) {
                        row("engine") { rowContext ->
                            SettingsLink(
                                context = rowContext,
                                title = stringResource(R.string.tts_settings_engine_title),
                                onClick = { picker = SpeechSetting.Engine },
                                value = engineLabel ?: stringResource(R.string.theme_system),
                            )
                        }
                    }
                    if (voiceResolution.options.isNotEmpty() || voiceResolution.effectiveKey != null) {
                        row("voice") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.tts_voice_title),
                                onClick = { picker = SpeechSetting.Voice },
                                value =
                                    if (selectedVoice == null) {
                                        stringResource(R.string.tts_voice_automatic)
                                    } else {
                                        effectiveVoiceLabel ?: stringResource(R.string.tts_voice_unavailable)
                                    },
                                subtitle =
                                    if (usingFallbackVoice) {
                                        stringResource(
                                            R.string.tts_voice_effective_fallback,
                                            effectiveVoiceLabel ?: stringResource(R.string.tts_voice_unavailable),
                                        )
                                    } else {
                                        null
                                    },
                                enabled = resolvedPackage != null,
                            )
                        }
                    }
                    row("rate") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.tts_settings_rate_title),
                            onClick = { picker = SpeechSetting.Rate },
                            value =
                                rateOverride?.let { ttsRateLabel(it, locale) }
                                    ?: stringResource(R.string.tts_settings_rate_system),
                        )
                    }
                }
            }
            if (status != null) {
                item {
                    SettingsCallout(
                        text = stringResource(status.textRes),
                        modifier = Modifier.testTag("speech.status"),
                        icon = status.iconRes,
                    )
                }
            }
            item {
                SettingsGroup(modifier = Modifier.testTag("speech.playback.group")) {
                    row("auto_read") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.tts_auto_read_default_global_title),
                            checked = ttsAutoReadPrefs.globalDefaultEnabled,
                            onCheckedChange = { appState.setTtsAutoReadGlobalDefault(it) },
                            modifier = Modifier.testTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG),
                            subtitle = stringResource(R.string.tts_auto_read_default_global_subtitle),
                        )
                    }
                    row("media_mix") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.tts_media_mix_title),
                            checked = mediaMix.enabled,
                            onCheckedChange = appState::setTtsMediaMixEnabled,
                            subtitle = stringResource(R.string.tts_media_mix_subtitle),
                        )
                    }
                    if (mediaMix.enabled) {
                        row("mix_volume") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.tts_media_mix_volume_title),
                                onClick = { picker = SpeechSetting.Volume },
                                value = stringResource(ttsMediaMixVolumeLabel(mediaMix.volume)),
                            )
                        }
                    }
                }
            }
            item {
                SettingsGroup(modifier = Modifier.testTag("speech.system.group")) {
                    row("refresh") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.refresh),
                            onClick = { refreshToken++ },
                        )
                    }
                    row("android_settings") { rowContext ->
                        SettingsAction(
                            context = rowContext,
                            title = stringResource(R.string.speech_android_settings),
                            onClick = { settingsFailed = !openSpeechSettings(context) },
                        )
                    }
                }
            }
            if (settingsFailed) {
                item {
                    SettingsCallout(
                        text = stringResource(R.string.speech_settings_failed),
                        modifier = Modifier.testTag("speech.settings_failed"),
                        isError = true,
                    )
                }
            }
        }
    }

    when (picker) {
        SpeechSetting.Engine ->
            SpeechChoiceDialog(
                title = stringResource(R.string.tts_settings_engine_title),
                choices =
                    engineChoice.engines.map { engine ->
                        val trust =
                            stringResource(
                                if (engine.trust == EngineTrust.Local) {
                                    R.string.tts_settings_engine_local
                                } else {
                                    R.string.tts_settings_engine_unknown
                                },
                            )
                        SpeechChoice(engine.label, engine.packageName == resolvedPackage, trust) {
                            picker = null
                            val warn =
                                requiresTtsTrustWarning(
                                    engine.packageName,
                                    appState.runtimeTrustForTtsSelectionWarning(engine.packageName),
                                    appState.ttsWarningPreferences,
                                )
                            if (warn) {
                                pendingEnginePackage = engine.packageName
                            } else {
                                appState.selectTtsEngine(engine.packageName)
                            }
                        }
                    },
                onDismiss = { picker = null },
            )
        SpeechSetting.Voice ->
            SpeechChoiceDialog(
                title = stringResource(R.string.tts_voice_title),
                choices =
                    listOf(
                        SpeechChoice(
                            title = stringResource(R.string.tts_voice_automatic),
                            selected = selectedVoice == null,
                            subtitle = stringResource(R.string.tts_voice_automatic_description),
                        ) {
                            picker = null
                            appState.selectTtsVoice(null)
                        },
                    ) +
                        voiceResolution.options.map { voice ->
                            speechVoiceChoice(voice, locale, selectedVoice == voice.key) {
                                picker = null
                                appState.selectTtsVoice(voice.key)
                            }
                        },
                onDismiss = { picker = null },
            )
        SpeechSetting.Rate ->
            SpeechChoiceDialog(
                title = stringResource(R.string.tts_settings_rate_title),
                choices =
                    listOf(
                        SpeechChoice(stringResource(R.string.tts_settings_rate_system), rateOverride == null) {
                            picker = null
                            appState.setTtsRateOverride(null)
                        },
                    ) +
                        TtsRatePreferences.PRESET_RATES.map { rate ->
                            SpeechChoice(ttsRateLabel(rate, locale), rateOverride == rate) {
                                picker = null
                                appState.setTtsRateOverride(rate)
                            }
                        } +
                        SpeechChoice(stringResource(R.string.tts_rate_custom), isTtsCustomRate(rateOverride)) {
                            picker = null
                            customRateOpen = true
                        },
                onDismiss = { picker = null },
            )
        SpeechSetting.Volume ->
            SpeechChoiceDialog(
                title = stringResource(R.string.tts_media_mix_volume_title),
                choices =
                    TtsMediaMixVolume.entries.map { volume ->
                        SpeechChoice(
                            title = stringResource(ttsMediaMixVolumeLabel(volume)),
                            selected = mediaMix.volume == volume,
                            subtitle = stringResource(ttsMediaMixVolumeDescription(volume)),
                        ) {
                            picker = null
                            appState.setTtsMediaMixVolume(volume)
                        }
                    },
                onDismiss = { picker = null },
            )
        null -> Unit
    }
    if (customRateOpen) {
        SpeechCustomRateDialog(
            initialRate = rateOverride ?: appState.ttsRatePreferences.resolvedRate(),
            locale = locale,
            onDismiss = { customRateOpen = false },
            onRateSelected = {
                appState.setTtsRateOverride(it)
                customRateOpen = false
            },
        )
    }
    pendingEnginePackage?.let { enginePackage ->
        TtsTrustWarningDialog(
            onProceed = {
                appState.acknowledgeTtsTrustWarning(enginePackage)
                pendingEnginePackage = null
                appState.selectTtsEngine(enginePackage)
            },
            onDismiss = { pendingEnginePackage = null },
        )
    }
}

/** One voice with its language and why it can or cannot be used. */
@Composable
internal fun speechVoiceChoice(
    voice: TtsVoiceOption,
    locale: Locale,
    selected: Boolean,
    onClick: () -> Unit,
): SpeechChoice {
    val localeLabel = Locale.forLanguageTag(voice.localeTag).getDisplayName(locale).ifBlank { voice.localeTag }
    val reason =
        stringResource(
            when (voice.unavailableReason) {
                TtsVoiceUnavailableReason.InvalidIdentity -> R.string.tts_voice_invalid_identity
                TtsVoiceUnavailableReason.NotInstalled -> R.string.tts_voice_not_installed
                TtsVoiceUnavailableReason.RequiresNetwork -> R.string.tts_voice_requires_network
                TtsVoiceUnavailableReason.Ambiguous -> R.string.tts_voice_ambiguous
                null -> R.string.tts_voice_available_offline
            },
        )
    return SpeechChoice(
        title = voice.label,
        selected = selected,
        subtitle = "$localeLabel. $reason",
        enabled = voice.selectable,
        accessibilityLabel = "${voice.label}. $localeLabel. $reason",
        onClick = onClick,
    )
}

/** A rate outside the presets, typed and applied only when it parses inside the supported range. */
@Suppress("FunctionNaming")
@Composable
private fun SpeechCustomRateDialog(
    initialRate: Float,
    locale: Locale,
    onDismiss: () -> Unit,
    onRateSelected: (Float) -> Unit,
) {
    val input = rememberTextFieldState(ttsRateInputValue(initialRate, locale))
    var attempted by remember { mutableStateOf(false) }
    val parsed = parseTtsRateInput(input.text.toString(), locale)
    WhiteNoiseAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tts_rate_custom)) },
        text = {
            WhiteNoiseTextField(
                state = input,
                modifier = Modifier.fillMaxWidth().testTag("speech.custom_rate"),
                label = { Text(stringResource(R.string.tts_settings_rate_title)) },
                supportingText = { Text(stringResource(R.string.tts_rate_custom_error)) },
                errorMessage =
                    if (attempted && parsed == null) stringResource(R.string.tts_rate_custom_error) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                lineLimits = TextFieldLineLimits.SingleLine,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    attempted = true
                    parsed?.let(onRateSelected)
                },
            ) { Text(stringResource(R.string.tts_rate_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The discovery state worth telling the user about, or null while everything is usable. */
private fun ttsStatusRes(
    appState: WhiteNoiseAppState,
    hasEffectiveVoice: Boolean,
): SpeechStatus? =
    when {
        shouldReportNoTtsEngine(appState.ttsResolution) ->
            SpeechStatus(R.string.speech_no_engine, R.drawable.ic_warning)
        !appState.ttsDiscoveryComplete -> SpeechStatus(R.string.speech_discovering, R.drawable.ic_info)
        !hasEffectiveVoice -> SpeechStatus(R.string.tts_voice_unavailable, R.drawable.ic_warning)
        else -> null
    }

/** Hands off to Android's own speech settings, falling back to the settings root. */
private fun openSpeechSettings(context: android.content.Context): Boolean =
    runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        .isSuccess

/** One discovery notice: its copy and the glyph that matches its severity. */
private data class SpeechStatus(
    @param:StringRes val textRes: Int,
    @param:DrawableRes val iconRes: Int,
)
