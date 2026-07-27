package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.TtsTrustWarningDialog
import dev.ipf.whitenoise.android.audio.tts.requiresTtsTrustWarning
import dev.ipf.whitenoise.android.audio.tts.shouldReportNoTtsEngine
import dev.ipf.whitenoise.android.state.TtsRatePreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextToSpeechScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val selectedOverride by appState.ttsEnginePreferences.selectedEnginePackage.collectAsState()
    val rateOverride by appState.ttsRatePreferences.rateOverride.collectAsState()
    val engineChoice = appState.ttsEngineChoice()
    val ttsResolution = appState.ttsResolution
    val reportNoEngine = shouldReportNoTtsEngine(ttsResolution)
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var pendingEnginePackage by remember { mutableStateOf<String?>(null) }
    var trustWarningOpen by remember { mutableStateOf(false) }
    var rateSheetOpen by remember { mutableStateOf(false) }
    var engineSheetOpen by remember { mutableStateOf(false) }

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
                // The whole page is one segmented list: two picker rows whose
                // options live in sheets, matching the Language pattern.
                SettingsGroup {
                    item {
                        SettingsRow(
                            title = stringResource(R.string.tts_settings_rate_title),
                            subtitle = rateOverride?.let(::ttsRateLabel) ?: stringResource(R.string.tts_settings_rate_system),
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
                                        ?: stringResource(R.string.tts_settings_rate_system),
                                icon = Icons.Filled.RecordVoiceOver,
                                onClick = { engineSheetOpen = true },
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
                        title = ttsRateLabel(rate),
                        selected = rateOverride == rate,
                        onClick = {
                            appState.setTtsRateOverride(rate)
                            rateSheetOpen = false
                        },
                    )
                }
            }
        }
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

/**
 * Matches the voice-note speed pill's rendering so both read as one system.
 * Non-integer rates format with the active locale's decimal separator
 * (0,75\u00d7 in de/fr), integers stay bare (1\u00d7).
 */
internal fun ttsRateLabel(rate: Float): String {
    val whole = rate.toInt()
    val number =
        if (rate == whole.toFloat()) {
            whole.toString()
        } else {
            java.text.NumberFormat
                .getNumberInstance()
                .format(rate.toDouble())
        }
    return "$number\u00d7"
}
