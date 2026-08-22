@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import android.icu.text.ListFormatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SettingsGroup

internal val MediaQuality.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaQuality.Low -> R.string.media_quality_low
            MediaQuality.Standard -> R.string.media_quality_standard
            MediaQuality.High -> R.string.media_quality_high
            MediaQuality.Original -> R.string.media_quality_original
        }

internal val MediaQuality.subtitleRes: Int
    @StringRes
    get() =
        when (this) {
            MediaQuality.Low -> R.string.media_quality_low_subtitle
            MediaQuality.Standard -> R.string.media_quality_standard_subtitle
            MediaQuality.High -> R.string.media_quality_high_subtitle
            MediaQuality.Original -> R.string.media_quality_original_subtitle
        }

/**
 * Settings -> Data and storage -> the per-type, per-network media
 * auto-download matrix (issue #407). One row per network whose subtitle lists
 * the enabled types; tapping opens a multi-select dialog that applies on OK
 * via [WhiteNoiseAppState.setMediaAutoDownload].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoDownloadDataScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    var dialogNetwork by remember { mutableStateOf<MediaAutoDownloadNetwork?>(null) }
    var confirmStopAutomatic by remember { mutableStateOf(false) }
    val automaticPaused = appState.automaticAttachmentDownloadsPaused()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_and_storage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        AutoDownloadSettingsList(
            appState = appState,
            automaticPaused = automaticPaused,
            contentPadding = padding,
            onNetworkSelected = { dialogNetwork = it },
            onBacklogAction = {
                if (automaticPaused) {
                    appState.restartAutomaticAttachmentDownloads()
                } else {
                    confirmStopAutomatic = true
                }
            },
        )
    }
    dialogNetwork?.let { network ->
        MediaTypesDialog(
            appState = appState,
            network = network,
            onDismiss = { dialogNetwork = null },
        )
    }
    if (confirmStopAutomatic) {
        StopAutomaticDownloadsDialog(
            onConfirm = {
                appState.stopAutomaticAttachmentDownloads()
                confirmStopAutomatic = false
            },
            onDismiss = { confirmStopAutomatic = false },
        )
    }
}

@Composable
private fun AutoDownloadSettingsList(
    appState: WhiteNoiseAppState,
    automaticPaused: Boolean,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onNetworkSelected: (MediaAutoDownloadNetwork) -> Unit,
    onBacklogAction: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AutoDownloadIntroduction() }
        item { AutoDownloadNetworkGroup(appState, onNetworkSelected) }
        item { AutoDownloadBacklogGroup(automaticPaused, onBacklogAction) }
        item { MediaQualityGroup(appState) }
        item { AutoDownloadPrivacyFooter() }
    }
}

@Composable
private fun AutoDownloadIntroduction() {
    Text(
        text = stringResource(R.string.media_auto_download_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun AutoDownloadNetworkGroup(
    appState: WhiteNoiseAppState,
    onNetworkSelected: (MediaAutoDownloadNetwork) -> Unit,
) {
    SettingsGroup(
        title = stringResource(R.string.media_auto_download_title),
        icon = Icons.Filled.Download,
    ) {
        MediaAutoDownloadNetwork.entries.forEach { network ->
            item {
                SettingsRow(
                    title = stringResource(network.labelRes),
                    subtitle = enabledTypesLabel(appState, network),
                    icon = network.rowIcon,
                    onClick = { onNetworkSelected(network) },
                )
            }
        }
    }
}

@Composable
private fun AutoDownloadBacklogGroup(
    automaticPaused: Boolean,
    onBacklogAction: () -> Unit,
) {
    val titleRes =
        if (automaticPaused) R.string.media_auto_download_restart else R.string.media_auto_download_stop
    val subtitleRes =
        if (automaticPaused) {
            R.string.media_auto_download_restart_subtitle
        } else {
            R.string.media_auto_download_stop_subtitle
        }
    SettingsGroup(
        title = stringResource(R.string.media_auto_download_backlog_title),
        icon = Icons.Filled.StopCircle,
    ) {
        item {
            SettingsRow(
                title = stringResource(titleRes),
                subtitle = stringResource(subtitleRes),
                icon = if (automaticPaused) Icons.Filled.RestartAlt else Icons.Filled.StopCircle,
                modifier = Modifier.testTag(AUTO_DOWNLOAD_BACKLOG_ACTION_TAG),
                onClick = onBacklogAction,
            )
        }
    }
}

@Composable
private fun AutoDownloadPrivacyFooter() {
    // Privacy floor + video/audio carve-out. The size knob and metadata strip
    // are orthogonal: photos never send location/device metadata, including
    // Original's source-byte path. Video and picked audio currently send as-is.
    Text(
        text = stringResource(R.string.media_quality_footer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun StopAutomaticDownloadsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.media_auto_download_stop)) },
        text = { Text(stringResource(R.string.media_auto_download_stop_confirmation)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.media_auto_download_stop_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

internal const val AUTO_DOWNLOAD_BACKLOG_ACTION_TAG = "auto_download_backlog_action"

// Locale-aware list of the types enabled for [network], or "none".
@Composable
private fun enabledTypesLabel(
    appState: WhiteNoiseAppState,
    network: MediaAutoDownloadNetwork,
): String {
    val enabled =
        MediaAutoDownloadType.entries
            .filter { appState.mediaAutoDownloadMatrix.isEnabled(it, network) }
            .map { stringResource(it.labelRes) }
    return if (enabled.isEmpty()) stringResource(R.string.none) else ListFormatter.getInstance().format(enabled)
}

// Multi-select checkbox dialog for one network; edits are local until OK.
@Composable
private fun MediaTypesDialog(
    appState: WhiteNoiseAppState,
    network: MediaAutoDownloadNetwork,
    onDismiss: () -> Unit,
) {
    var pending by
        remember(network) {
            mutableStateOf(
                MediaAutoDownloadType.entries
                    .filter { appState.mediaAutoDownloadMatrix.isEnabled(it, network) }
                    .toSet(),
            )
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(network.labelRes)) },
        text = {
            Column {
                MediaAutoDownloadType.entries.forEach { type ->
                    val checked = type in pending
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { pending = if (it) pending + type else pending - type },
                                ).padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(stringResource(type.labelRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    MediaAutoDownloadType.entries.forEach { type ->
                        val want = type in pending
                        if (want != appState.mediaAutoDownloadMatrix.isEnabled(type, network)) {
                            appState.setMediaAutoDownload(type, network, want)
                        }
                    }
                    onDismiss()
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal val MediaAutoDownloadNetwork.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaAutoDownloadNetwork.WiFi -> R.string.network_wifi
            MediaAutoDownloadNetwork.Mobile -> R.string.network_mobile
            MediaAutoDownloadNetwork.Roaming -> R.string.network_roaming
            MediaAutoDownloadNetwork.Metered -> R.string.network_metered
        }

private val MediaAutoDownloadNetwork.rowIcon: ImageVector
    get() =
        when (this) {
            MediaAutoDownloadNetwork.WiFi -> Icons.Filled.Wifi
            MediaAutoDownloadNetwork.Mobile -> Icons.Filled.SignalCellularAlt
            MediaAutoDownloadNetwork.Roaming -> Icons.Filled.Public
            MediaAutoDownloadNetwork.Metered -> Icons.Filled.DataUsage
        }

internal val MediaAutoDownloadType.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaAutoDownloadType.Image -> R.string.media_type_images
            MediaAutoDownloadType.Audio -> R.string.media_type_audio
            MediaAutoDownloadType.Video -> R.string.media_type_video
            MediaAutoDownloadType.Document -> R.string.media_type_documents
        }

// One picker row whose options open in a sheet, matching the Language pattern.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaQualityGroup(appState: WhiteNoiseAppState) {
    var showSheet by remember { mutableStateOf(false) }
    SettingsGroup {
        item {
            SettingsRow(
                title = stringResource(R.string.media_quality_title),
                subtitle = stringResource(appState.mediaQuality.labelRes),
                icon = Icons.Filled.HighQuality,
                onClick = { showSheet = true },
            )
        }
    }
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
                MediaQuality.entries.forEach { quality ->
                    SelectableSettingsRowWithSubtitle(
                        title = stringResource(quality.labelRes),
                        subtitle = stringResource(quality.subtitleRes),
                        selected = appState.mediaQuality == quality,
                        onClick = {
                            appState.updateMediaQuality(quality)
                            showSheet = false
                        },
                    )
                }
            }
        }
    }
}
