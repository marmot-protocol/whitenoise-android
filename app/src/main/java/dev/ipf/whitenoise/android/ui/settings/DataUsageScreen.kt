@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MediaAutoDownloadMatrix
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.whiteNoiseDialogSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

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

/** The prototype's media order: photos, videos, audio, then files. */
private val DownloadMediaOrder =
    listOf(
        MediaAutoDownloadType.Image,
        MediaAutoDownloadType.Video,
        MediaAutoDownloadType.Audio,
        MediaAutoDownloadType.Document,
    )

/**
 * Data Usage: one row per media type listing the networks that may download it (a dialog of switches that write the
 * account's matrix immediately, M105), Reset download settings, the automatic download queue's Stop/Restart action
 * with its confirmation, and the sent-media quality choice.
 */
@Suppress("LongMethod")
@Composable
internal fun DataUsageScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    var picker by rememberSaveable { mutableStateOf<MediaAutoDownloadType?>(null) }
    var qualityPicker by rememberSaveable { mutableStateOf(false) }
    var stopConfirmation by rememberSaveable { mutableStateOf(false) }
    val matrix = appState.mediaAutoDownloadMatrix
    val paused = appState.automaticAttachmentDownloadsPaused()
    val networkLabels = MediaAutoDownloadNetwork.entries.associateWith { stringResource(it.labelRes) }
    val qualityLabels = MediaQuality.entries.associateWith { stringResource(it.labelRes) }
    val qualityDetails = MediaQuality.entries.associateWith { stringResource(it.subtitleRes) }
    SettingsScaffold(title = stringResource(R.string.data_usage_title), onBack = onBack) {
        SettingsList {
            item { SettingsSection(stringResource(R.string.download_automatic)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("data_usage.downloads.group")) {
                    DownloadMediaOrder.forEach { type ->
                        row(type.preferenceKey) { context ->
                            val allowed = MediaAutoDownloadNetwork.entries.filter { matrix.isEnabled(type, it) }
                            SettingsLink(
                                context = context,
                                title = stringResource(type.labelRes),
                                onClick = { picker = type },
                                value =
                                    allowed
                                        .map(networkLabels::getValue)
                                        .joinToString()
                                        .ifEmpty { stringResource(R.string.download_never) },
                            )
                        }
                    }
                    row("reset") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(R.string.download_reset),
                            onClick = { resetMediaAutoDownload(appState) },
                            subtitle = stringResource(R.string.download_reset_help),
                            enabled = matrix != MediaAutoDownloadMatrix.DEFAULT,
                        )
                    }
                }
            }
            item { SettingsExplainer(stringResource(R.string.download_rules_help)) }
            item { SettingsSection(stringResource(R.string.download_queue)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("data_usage.queue")) {
                    row("queue") { context ->
                        SettingsAction(
                            context = context,
                            title = stringResource(if (paused) R.string.download_restart else R.string.download_stop),
                            onClick = {
                                if (paused) appState.restartAutomaticAttachmentDownloads() else stopConfirmation = true
                            },
                            modifier = Modifier.testTag("data_usage.queue.action"),
                            subtitle =
                                stringResource(if (paused) R.string.download_paused else R.string.download_enabled),
                        )
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.download_sent_media)) }
            item {
                SettingsGroup(modifier = Modifier.testTag("data_usage.sent_media.group")) {
                    row("quality") { context ->
                        SettingsLink(
                            context = context,
                            title = stringResource(R.string.download_quality),
                            onClick = { qualityPicker = true },
                            value = qualityLabels.getValue(appState.mediaQuality),
                        )
                    }
                }
            }
            item {
                SettingsExplainer(stringResource(R.string.download_quality_help))
                SettingsExplainer(stringResource(R.string.media_quality_footer))
            }
        }
    }
    picker?.let { type ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(stringResource(type.labelRes)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("download.network.options")) {
                    DownloadNetworkOptions(
                        networks = MediaAutoDownloadNetwork.entries.filter { matrix.isEnabled(type, it) }.toSet(),
                        onChange = { network, enabled -> appState.setMediaAutoDownload(type, network, enabled) },
                    )
                    Text(
                        stringResource(R.string.download_rules_help),
                        modifier = Modifier.padding(top = WhiteNoiseSpacing.FormField),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { picker = null }) { Text(stringResource(R.string.download_done)) }
            },
        )
    }
    if (qualityPicker) {
        ChoiceDialog(
            title = stringResource(R.string.download_quality),
            values = MediaQuality.entries,
            selected = appState.mediaQuality,
            label = qualityLabels::getValue,
            onDismiss = { qualityPicker = false },
            onSelect = {
                appState.updateMediaQuality(it)
                qualityPicker = false
            },
            subtitle = qualityDetails::getValue,
        )
    }
    if (stopConfirmation) {
        WhiteNoiseAlertDialog(
            onDismissRequest = { stopConfirmation = false },
            title = { Text(stringResource(R.string.download_stop)) },
            text = { Text(stringResource(R.string.download_stop_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        appState.stopAutomaticAttachmentDownloads()
                        stopConfirmation = false
                    },
                    modifier = Modifier.testTag("download.stop.confirm"),
                ) { Text(stringResource(R.string.download_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { stopConfirmation = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** One switch per network condition; each change is one validated write to the account's matrix. */
@Composable
internal fun DownloadNetworkOptions(
    networks: Set<MediaAutoDownloadNetwork>,
    enabled: Boolean = true,
    onChange: (MediaAutoDownloadNetwork, Boolean) -> Unit,
) {
    MediaAutoDownloadNetwork.entries.forEach { network ->
        DownloadSwitch(
            title = stringResource(network.labelRes),
            checked = network in networks,
            tag = "download.network.${network.name}",
            enabled = enabled,
            onChange = { onChange(network, it) },
        )
    }
}

/** A dialog switch row: the whole row toggles and carries the switch semantics. */
@Composable
internal fun DownloadSwitch(
    title: String,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .whiteNoiseDialogSelection(checked && enabled)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .heightIn(min = DownloadSwitchMinHeight)
            .padding(horizontal = WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = LocalContentColor.current.let { if (enabled) it else it.copy(alpha = DISABLED_ALPHA) },
        )
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Every cell back to the shipped default; unchanged cells are not rewritten. */
private fun resetMediaAutoDownload(appState: WhiteNoiseAppState) {
    MediaAutoDownloadType.entries.forEach { type ->
        MediaAutoDownloadNetwork.entries.forEach { network ->
            appState.setMediaAutoDownload(type, network, MediaAutoDownloadMatrix.DEFAULT.isEnabled(type, network))
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
            MediaAutoDownloadType.Image -> R.string.download_photos
            MediaAutoDownloadType.Audio -> R.string.download_audio
            MediaAutoDownloadType.Video -> R.string.download_videos
            MediaAutoDownloadType.Document -> R.string.download_files
        }

private val DownloadSwitchMinHeight = 48.dp
private const val DISABLED_ALPHA = 0.38f
