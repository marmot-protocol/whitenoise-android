@file:Suppress("MatchingDeclarationName") // The screen owns its small presentation state declaration.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** The build this copy of White Noise was made from, as the Developer and About surfaces report it. */
internal data class DeveloperBuildFacts(
    val versionName: String,
    val buildNumber: String,
    val mdkShortSha: String,
    val staging: Boolean = false,
)

/** The installed variant's facts; the staging resource overlay remains authoritative for its badge. */
internal fun developerBuildFacts(staging: Boolean): DeveloperBuildFacts {
    val buildNumber = BuildConfig.VERSION_CODE.toString()
    return DeveloperBuildFacts(BuildConfig.VERSION_NAME, buildNumber, BuildConfig.MDK_SHORT_SHA, staging)
}

/**
 * Developer Tools: the warning, the switch that owns developer mode, Key Packages as a recovery destination,
 * the debugging surfaces the switch reveals, and the build this app was made from. Telemetry and audit logs
 * stay in Privacy & Security — they are privacy controls, not developer tools.
 */
@Suppress("FunctionNaming")
@Composable
internal fun DeveloperScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyPackages: () -> Unit,
) {
    DeveloperContent(
        developerMode = appState.developerMode,
        streamingDebug = appState.streamingDebugMode,
        build = developerBuildFacts(booleanResource(R.bool.staging_build)),
        onDeveloperModeChange = { appState.updateDeveloperMode(it) },
        onStreamingDebugChange = { appState.updateStreamingDebugMode(it) },
        onBack = onBack,
        onOpenDiagnostics = onOpenDiagnostics,
        onOpenKeyPackages = onOpenKeyPackages,
    )
}

/** The list itself. Debugging appears only while developer mode is on; Key Packages never depends on it. */
@Suppress("FunctionNaming", "LongParameterList", "LongMethod")
@Composable
internal fun DeveloperContent(
    developerMode: Boolean,
    streamingDebug: Boolean,
    build: DeveloperBuildFacts,
    onDeveloperModeChange: (Boolean) -> Unit,
    onStreamingDebugChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenKeyPackages: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_developer_tools), onBack = onBack) {
        SettingsList {
            item {
                SettingsCallout(
                    text = stringResource(R.string.developer_testing_only_detail),
                    modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("developer.warning"),
                    title = stringResource(R.string.developer_testing_only),
                    icon = R.drawable.ic_warning,
                )
            }
            item {
                SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("developer.mode")) {
                    row("developer_mode") { context ->
                        SettingsSwitch(
                            context = context,
                            title = stringResource(R.string.developer_mode),
                            checked = developerMode,
                            onCheckedChange = onDeveloperModeChange,
                            modifier = Modifier.testTag("developer.mode.switch"),
                        )
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.developer_mode_subtitle)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("developer.key_packages")) {
                    row("key_packages") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.key_packages),
                            onClick = onOpenKeyPackages,
                            modifier = Modifier.testTag("developer.key_packages.row"),
                            subtitle = stringResource(R.string.key_packages_settings_subtitle),
                        )
                    }
                }
            }
            if (developerMode) {
                item { SettingsSection(stringResource(R.string.developer_debugging)) }
                item {
                    SettingsGroup(modifier = Modifier.testTag("developer.debugging")) {
                        row("streaming_debug") { context ->
                            SettingsSwitch(
                                context = context,
                                title = stringResource(R.string.streaming_debug),
                                checked = streamingDebug,
                                onCheckedChange = onStreamingDebugChange,
                                modifier = Modifier.testTag("developer.streaming_debug"),
                            )
                        }
                        row("diagnostics") { context ->
                            SettingsLink(
                                context = context,
                                title = stringResource(R.string.diagnostics),
                                onClick = onOpenDiagnostics,
                                modifier = Modifier.testTag("developer.diagnostics"),
                                subtitle = stringResource(R.string.diagnostics_settings_subtitle),
                            )
                        }
                    }
                }
                item { SettingsExplainer(stringResource(R.string.streaming_debug_subtitle)) }
            }
            item { SettingsSection(stringResource(R.string.about)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("developer.build")) {
                    row("version") { context ->
                        SettingsValue(context, stringResource(R.string.about_version), build.versionName)
                    }
                    row("build") { context ->
                        SettingsValue(context, stringResource(R.string.about_build), build.buildNumber)
                    }
                    row("mdk") { context ->
                        SettingsValue(context, stringResource(R.string.about_mdk), build.mdkShortSha)
                    }
                }
            }
            if (build.staging) {
                item { DeveloperStagingBadge() }
            }
        }
    }
}

/** Retains the production variant badge beside build facts without changing the Settings home footer. */
@Suppress("FunctionNaming")
@Composable
private fun DeveloperStagingBadge() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = PillShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.testTag("developer.staging"),
        ) {
            Text(
                text = stringResource(R.string.settings_staging_badge),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}
