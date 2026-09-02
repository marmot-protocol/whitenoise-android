package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import dev.ipf.whitenoise.android.ui.common.SettingsGroup
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadGlobalDefaultRow

/** Presents engine-scoped voices and the explicit speech-over-media policy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextToSpeechScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val selectedOverride by appState.ttsEnginePreferences.selectedEnginePackage.collectAsState()
    val rateOverride by appState.ttsRatePreferences.rateOverride.collectAsState()
    val mediaMix by appState.ttsMediaMixPreferences.state.collectAsState()
    val ttsAutoReadPrefs by appState.ttsAutoReadPreferences.state.collectAsState()
    val engineChoice = appState.ttsEngineChoice()
    val ttsResolution = appState.ttsResolution
    val reportNoEngine = shouldReportNoTtsEngine(ttsResolution)
    val lifecycleOwner = LocalLifecycleOwner.current
    val locale = LocalConfiguration.current.locales[0]
    var refreshToken by remember { mutableIntStateOf(0) }
    var pendingEnginePackage by remember { mutableStateOf<String?>(null) }
    var trustWarningOpen by remember { mutableStateOf(false) }
    var rateSheetOpen by remember { mutableStateOf(false) }
    var customRateOpen by remember { mutableStateOf(false) }
    var engineSheetOpen by remember { mutableStateOf(false) }
    var mixVolumeSheetOpen by remember { mutableStateOf(false) }
    var voiceSheetOpen by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshToken++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshToken) {
        appState.refreshTtsAvailability()
    }

    val showEngineChooser =
        engineChoice.showEngineChooser ||
            (selectedOverride != null && engineChoice.engines.size > 1)
    val resolvedPackage = appState.resolvedTtsEnginePackage()

    fun selectEngine(enginePackage: String) {
        appState.selectTtsEngine(enginePackage)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tts_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // Explainer sits on the background, matching the other pages.
                Text(
                    text =
                        when {
                            reportNoEngine -> stringResource(R.string.tts_settings_explainer_no_engine)
                            !appState.ttsDiscoveryComplete -> stringResource(R.string.tts_settings_explainer_discovering)
                            else -> stringResource(R.string.tts_settings_explainer_usable)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item {
                // Keep all speech preferences in one segmented list. Picker
                // options live in sheets, matching the Language pattern.
                SettingsGroup {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.tts_settings_rate_title),
                            subtitle =
                                rateOverride?.let { ttsRateLabel(it, locale) }
                                    ?: stringResource(R.string.tts_settings_rate_system),
                            icon = Icons.Filled.Speed,
                            onClick = { rateSheetOpen = true },
                        )
                    }
                    if (showEngineChooser && engineChoice.engines.isNotEmpty()) {
                        item {
                            SettingsRow(
                                title = stringResource(R.string.tts_settings_engine_title),
                                subtitle =
                                    engineChoice.engines.firstOrNull { it.packageName == resolvedPackage }?.label
                                        ?: stringResource(R.string.theme_system),
                                icon = Icons.Filled.RecordVoiceOver,
                                onClick = { engineSheetOpen = true },
                            )
                        }
                    }
                    if (
                        appState.ttsVoiceResolution.options.isNotEmpty() ||
                        appState.ttsVoiceResolution.effectiveKey != null ||
                        appState.ttsVoiceResolution.requestedKey != null
                    ) {
                        item {
                            val voiceResolution = appState.ttsVoiceResolution
                            val effectiveLabel =
                                voiceResolution.options
                                    .firstOrNull { it.key == voiceResolution.effectiveKey }
                                    ?.label
                                    ?: voiceResolution.effectiveKey?.voiceName
                                    ?: stringResource(R.string.tts_voice_unavailable)
                            val subtitle =
                                if (
                                    voiceResolution.requestedKey != null &&
                                    !voiceResolution.isUsingRequestedVoice
                                ) {
                                    stringResource(R.string.tts_voice_effective_fallback, effectiveLabel)
                                } else {
                                    effectiveLabel
                                }
                            SettingsRow(
                                title = stringResource(R.string.tts_voice_title),
                                subtitle = subtitle,
                                icon = Icons.Filled.RecordVoiceOver,
                                onClick = { voiceSheetOpen = true },
                            )
                        }
                    }
                    item {
                        TtsAutoReadGlobalDefaultRow(
                            checked = ttsAutoReadPrefs.globalDefaultEnabled,
                            onCheckedChange = { appState.setTtsAutoReadGlobalDefault(it) },
                        )
                    }
                    item {
                        ttsMediaMixToggleRow(
                            checked = mediaMix.enabled,
                            onCheckedChange = appState::setTtsMediaMixEnabled,
                        )
                    }
                    if (mediaMix.enabled) {
                        item {
                            SettingsRow(
                                title = stringResource(R.string.tts_media_mix_volume_title),
                                subtitle = stringResource(ttsMediaMixVolumeLabel(mediaMix.volume)),
                                icon = Icons.AutoMirrored.Filled.VolumeDown,
                                onClick = { mixVolumeSheetOpen = true },
                            )
                        }
                    }
                }
            }
        }
    }

    if (rateSheetOpen) {
        // Presets, not a slider: the framework only validates rate > 0 and
        // engines disagree past the ends, so a bounded set with a System entry
        // (follow the OS accessibility rate) is safer.
        ModalBottomSheet(onDismissRequest = { rateSheetOpen = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                SelectableSettingsRow(
                    title = stringResource(R.string.tts_settings_rate_system),
                    selected = rateOverride == null,
                    onClick = {
                        appState.setTtsRateOverride(null)
                        rateSheetOpen = false
                    },
                )
                TtsRatePreferences.PRESET_RATES.forEach { rate ->
                    SelectableSettingsRow(
                        title = ttsRateLabel(rate, locale),
                        selected = rateOverride == rate,
                        onClick = {
                            appState.setTtsRateOverride(rate)
                            rateSheetOpen = false
                        },
                    )
                }
                SelectableSettingsRow(
                    title = stringResource(R.string.tts_rate_custom),
                    selected = isTtsCustomRate(rateOverride),
                    onClick = {
                        rateSheetOpen = false
                        customRateOpen = true
                    },
                )
            }
        }
    }
    if (customRateOpen) {
        TtsCustomRateDialog(
            initialRate = rateOverride ?: appState.ttsRatePreferences.resolvedRate(),
            onDismiss = { customRateOpen = false },
            onRateSelected = { rate ->
                appState.setTtsRateOverride(rate)
                customRateOpen = false
            },
        )
    }
    if (engineSheetOpen) {
        ModalBottomSheet(onDismissRequest = { engineSheetOpen = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                engineChoice.engines.forEach { engine ->
                    val trustLabel =
                        when (engine.trust) {
                            EngineTrust.Local -> stringResource(R.string.tts_settings_engine_local)
                            EngineTrust.Unknown -> stringResource(R.string.tts_settings_engine_unknown)
                        }
                    SelectableSettingsRowWithSubtitle(
                        title = engine.label,
                        subtitle = trustLabel,
                        selected = resolvedPackage == engine.packageName,
                        onClick = {
                            engineSheetOpen = false
                            if (
                                requiresTtsTrustWarning(
                                    engine.packageName,
                                    appState.runtimeTrustForTtsSelectionWarning(engine.packageName),
                                    appState.ttsWarningPreferences,
                                )
                            ) {
                                pendingEnginePackage = engine.packageName
                                trustWarningOpen = true
                            } else {
                                selectEngine(engine.packageName)
                            }
                        },
                    )
                }
            }
        }
    }
    if (mixVolumeSheetOpen) {
        ModalBottomSheet(onDismissRequest = { mixVolumeSheetOpen = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                TtsMediaMixVolume.entries.forEach { volume ->
                    SelectableSettingsRowWithSubtitle(
                        title = stringResource(ttsMediaMixVolumeLabel(volume)),
                        subtitle = stringResource(ttsMediaMixVolumeDescription(volume)),
                        selected = mediaMix.volume == volume,
                        onClick = {
                            appState.setTtsMediaMixVolume(volume)
                            mixVolumeSheetOpen = false
                        },
                    )
                }
            }
        }
    }
    if (voiceSheetOpen) {
        val voiceResolution = appState.ttsVoiceResolution
        val enginePackage = appState.resolvedTtsEnginePackage()
        val selectedVoice = enginePackage?.let(appState.ttsVoicePreferences::selectedVoice)
        ModalBottomSheet(onDismissRequest = { voiceSheetOpen = false }) {
            LazyColumn(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                item {
                    SelectableSettingsRowWithSubtitle(
                        title = stringResource(R.string.tts_voice_automatic),
                        subtitle = stringResource(R.string.tts_voice_automatic_description),
                        selected = selectedVoice == null,
                        onClick = {
                            appState.selectTtsVoice(null)
                            voiceSheetOpen = false
                        },
                    )
                }
                items(voiceResolution.options) { voice ->
                    ttsVoicePickerRow(
                        voice = voice,
                        displayLocale = locale,
                        selected = selectedVoice == voice.key,
                        onClick = {
                            appState.selectTtsVoice(voice.key)
                            voiceSheetOpen = false
                        },
                    )
                }
            }
        }
    }
    if (trustWarningOpen && pendingEnginePackage != null) {
        val enginePackage = pendingEnginePackage!!
        TtsTrustWarningDialog(
            onProceed = {
                appState.acknowledgeTtsTrustWarning(enginePackage)
                trustWarningOpen = false
                selectEngine(enginePackage)
                pendingEnginePackage = null
            },
            onDismiss = {
                trustWarningOpen = false
                pendingEnginePackage = null
            },
        )
    }
}

/** Voice picker row whose merged semantics name locale and availability. */
@Composable
internal fun ttsVoicePickerRow(
    voice: TtsVoiceOption,
    displayLocale: java.util.Locale,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val localeLabel =
        java.util.Locale
            .forLanguageTag(voice.localeTag)
            .getDisplayName(displayLocale)
            .ifBlank { voice.localeTag }
    val reason =
        when (voice.unavailableReason) {
            TtsVoiceUnavailableReason.InvalidIdentity -> stringResource(R.string.tts_voice_invalid_identity)
            TtsVoiceUnavailableReason.NotInstalled -> stringResource(R.string.tts_voice_not_installed)
            TtsVoiceUnavailableReason.RequiresNetwork -> stringResource(R.string.tts_voice_requires_network)
            TtsVoiceUnavailableReason.Ambiguous -> stringResource(R.string.tts_voice_ambiguous)
            null -> stringResource(R.string.tts_voice_available_offline)
        }
    SelectableSettingsRowWithSubtitle(
        title = voice.label,
        subtitle = "$localeLabel. $reason",
        selected = selected,
        enabled = voice.selectable,
        accessibilityLabel = "${voice.label}. $localeLabel. $reason",
        onClick = onClick,
    )
}

/** Accessible opt-in switch for the constrained active-media mode. */
@Composable
internal fun ttsMediaMixToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val title = stringResource(R.string.tts_media_mix_title)
    val subtitle = stringResource(R.string.tts_media_mix_subtitle)
    SettingsSwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        contentSpacing = 16.dp,
        switchModifier =
            Modifier.semantics {
                contentDescription = "$title. $subtitle"
            },
        onCheckedChange = onCheckedChange,
    )
}

/** String label for one bounded speech-over-media volume preset. */
@androidx.annotation.StringRes
internal fun ttsMediaMixVolumeLabel(volume: TtsMediaMixVolume): Int =
    when (volume) {
        TtsMediaMixVolume.QUIET -> R.string.tts_media_mix_volume_quiet
        TtsMediaMixVolume.MEDIUM -> R.string.tts_media_mix_volume_medium
        TtsMediaMixVolume.LOUD -> R.string.tts_media_mix_volume_loud
    }

/** TalkBack-visible explanation for one mix volume preset. */
@androidx.annotation.StringRes
internal fun ttsMediaMixVolumeDescription(volume: TtsMediaMixVolume): Int =
    when (volume) {
        TtsMediaMixVolume.QUIET -> R.string.tts_media_mix_volume_quiet_description
        TtsMediaMixVolume.MEDIUM -> R.string.tts_media_mix_volume_medium_description
        TtsMediaMixVolume.LOUD -> R.string.tts_media_mix_volume_loud_description
    }

/**
 * Matches the voice-note speed pill's rendering so both read as one system.
 * Non-integer rates format with the active locale's decimal separator
 * (0,75\u00d7 in de/fr), integers stay bare (1\u00d7).
 */
internal fun ttsRateLabel(
    rate: Float,
    locale: java.util.Locale,
): String {
    val whole = rate.toInt()
    val number =
        if (rate == whole.toFloat()) {
            whole.toString()
        } else {
            java.text.NumberFormat
                .getNumberInstance(locale)
                .format(rate.toDouble())
        }
    return "$number\u00d7"
}
