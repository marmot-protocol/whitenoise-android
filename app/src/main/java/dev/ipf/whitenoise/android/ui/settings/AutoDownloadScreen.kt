package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.SectionCard

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
 * auto-download matrix (issue #407). Renders four grouped lists (one per
 * network type), each with four [SettingsSwitchRow] toggles. Toggles persist
 * immediately via [WhiteNoiseAppState.setMediaAutoDownload]; there is no Save
 * button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoDownloadDataScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(
                    text = stringResource(R.string.media_auto_download_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                MediaQualitySettingsCard(appState)
            }
            MediaAutoDownloadNetwork.entries.forEach { network ->
                item {
                    SectionCard(title = stringResource(network.labelRes)) {
                        MediaAutoDownloadType.entries.forEach { type ->
                            SettingsSwitchRow(
                                title = stringResource(type.labelRes),
                                subtitle = null,
                                checked = appState.mediaAutoDownloadMatrix.isEnabled(type, network),
                                onCheckedChange = { appState.setMediaAutoDownload(type, network, it) },
                            )
                        }
                    }
                }
            }
        }
    }
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

internal val MediaAutoDownloadType.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaAutoDownloadType.Image -> R.string.media_type_images
            MediaAutoDownloadType.Audio -> R.string.media_type_audio
            MediaAutoDownloadType.Video -> R.string.media_type_video
            MediaAutoDownloadType.Document -> R.string.media_type_documents
        }

@Composable
private fun MediaQualitySettingsCard(appState: WhiteNoiseAppState) {
    SectionCard(title = stringResource(R.string.media_quality_title)) {
        Column(Modifier.selectableGroup()) {
            MediaQuality.entries.forEach { quality ->
                SelectableSettingsRowWithSubtitle(
                    title = stringResource(quality.labelRes),
                    subtitle = stringResource(quality.subtitleRes),
                    selected = appState.mediaQuality == quality,
                    onClick = { appState.updateMediaQuality(quality) },
                )
            }
        }
        // Privacy floor + video/audio carve-out. The size knob and metadata
        // strip are orthogonal: photos never send location/device metadata,
        // including Original's source-byte path. Video and picked audio
        // currently send as-is.
        Text(
            text = stringResource(R.string.media_quality_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
