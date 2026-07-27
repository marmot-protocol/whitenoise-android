package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.SectionCard

// Reached only through the hidden gate in About (see AboutScreen), so the row
// is invisible to non-developers. Telemetry and audit logs stay in Device
// privacy — they are privacy controls, not developer tools.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DeveloperScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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
        }
    }
}
