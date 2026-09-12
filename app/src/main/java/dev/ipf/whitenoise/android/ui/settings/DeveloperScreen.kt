package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.SectionCard
import dev.ipf.whitenoise.android.ui.theme.PillShape

// Reached from the Developer Tools row in Settings, which owns the developer
// switch itself. Telemetry and audit logs stay in Device privacy — they are
// privacy controls, not developer tools.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DeveloperScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyPackages: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(Modifier.animateContentSize(MaterialTheme.motionScheme.fastSpatialSpec())) {
                    SectionCard(title = stringResource(R.string.developer)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.developer_mode),
                            subtitle = stringResource(R.string.developer_mode_subtitle),
                            checked = appState.developerMode,
                            onCheckedChange = { appState.updateDeveloperMode(it) },
                        )
                        AppDivider(Modifier.padding(vertical = 12.dp))
                        SettingsRow(
                            title = stringResource(R.string.key_packages),
                            subtitle = stringResource(R.string.key_packages_settings_subtitle),
                            onClick = onOpenKeyPackages,
                        )
                        if (appState.developerMode) {
                            AppDivider(Modifier.padding(vertical = 12.dp))
                            SettingsRow(
                                title = stringResource(R.string.diagnostics),
                                subtitle = stringResource(R.string.diagnostics_settings_subtitle),
                                onClick = onOpenDiagnostics,
                            )
                            AppDivider(Modifier.padding(vertical = 12.dp))
                            SettingsSwitchRow(
                                title = stringResource(R.string.streaming_debug),
                                subtitle = stringResource(R.string.streaming_debug_subtitle),
                                checked = appState.streamingDebugMode,
                                onCheckedChange = { appState.updateStreamingDebugMode(it) },
                            )
                        }
                    }
                }
            }
            item { DeveloperBuildInfo() }
        }
    }
}

/** Release metadata the Settings home no longer shows: version, MDK revision and the staging badge. */
@Composable
@Suppress("FunctionNaming")
private fun DeveloperBuildInfo() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.settings_mdk_version_label, BuildConfig.MDK_SHORT_SHA),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // Main resources keep this false; only staging overrides it.
        if (booleanResource(R.bool.staging_build)) {
            Surface(
                shape = PillShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.settings_staging_badge),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}
