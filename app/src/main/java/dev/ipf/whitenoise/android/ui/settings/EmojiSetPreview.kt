@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import android.icu.text.ListFormatter
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.EmojiSetsState
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.util.Locale

/** The set preview's Load emoji action. */
internal const val EMOJI_SET_LOAD_TEST_TAG = "emoji_sets.load"

private val CoverSize = 72.dp
private val CoverArtSize = 56.dp
private const val MAX_NAMED_HOSTS = 3

/**
 * One set version: who it is, what installing does, and its emoji as a grid. Remote previews require the user
 * to load them; an installed snapshot of this version renders from the device at once.
 */
@Suppress("LongParameterList")
@Composable
internal fun EmojiSetPreview(
    state: EmojiSetsState,
    set: EmojiSetFfi,
    images: EmojiSetImages?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    BoxWithConstraints {
        val columns = gridColumns(maxWidth)
        val rows = remember(set.emoji, columns) { set.emoji.chunked(columns) }
        val pulse = if (images?.hasLoadingTiles == true) rememberTilePulse() else StillPulse
        SettingsList {
            item(key = "set.header") { EmojiSetPreviewHeader(set, images) }
            // Install, Update and Load emoji all contact the image hosts, so the disclosure comes before each of them.
            if (images?.local != true || set.installed?.updateAvailable == true) {
                item(key = "set.hosts") { EmojiSetHostDisclosure(set) }
            }
            val canLoadImages = rows.isNotEmpty() && state.progress == null
            if (images != null && !images.requested && canLoadImages) {
                item(key = "set.load") {
                    EmojiSetLoadAction(enabled = !state.busy, onLoad = images::request)
                }
            }
            item(key = "set.actions") { EmojiSetActions(state, set, onInstall, onCancel, onRemove, onCheckUpdate) }
            if (state.progress == null || state.notice != null || state.installedWithSkips != null) {
                item(key = "set.status") { EmojiSetStatus(state, showProgress = state.progress == null) }
            }
            if (images?.requested == true) {
                rows.forEachIndexed { index, row ->
                    item(key = "set.row.$index") { EmojiSetTileRow(row, columns, images, pulse) }
                }
            }
        }
    }
}

/** Cover, title, size and install state, then the author's description. */
@Composable
private fun EmojiSetPreviewHeader(
    set: EmojiSetFfi,
    images: EmojiSetImages?,
) {
    if (images != null) LaunchedEffect(images, images.requested, images.coverAttempt) { images.loadCover() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
        ) {
            EmojiSetCover(images)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related / 2)) {
                EmojiLabel(
                    set.displayTitle(),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    emojiSetSummary(set),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        set.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "12 emoji", plus the install state when there is one. */
@Composable
private fun emojiSetSummary(set: EmojiSetFfi): String {
    val count = pluralStringResource(R.plurals.emoji_sets_item_count, set.emoji.size, set.emoji.size)
    val installed = set.installed ?: return count
    val status =
        stringResource(
            if (installed.updateAvailable) R.string.emoji_sets_update_available else R.string.emoji_sets_installed,
        )
    return stringResource(R.string.emoji_sets_summary, count, status)
}

/** The cover when loaded, else the first loaded emoji, else the set icon. */
@Composable
private fun EmojiSetCover(images: EmojiSetImages?) {
    val art = (images?.cover as? EmojiSetImage.Ready)?.art ?: images?.firstReady()
    Surface(
        modifier = Modifier.size(CoverSize),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (art != null) {
                EmojiArtImage(art, contentDescription = null, modifier = Modifier.size(CoverArtSize))
            } else {
                Icon(
                    painterResource(R.drawable.ic_category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Install, update, check or remove; during an install, only its measured progress and Cancel. Remove confirms first,
 * since getting the set back means downloading it again.
 */
@Suppress("LongParameterList")
@Composable
private fun EmojiSetActions(
    state: EmojiSetsState,
    set: EmojiSetFfi,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        val progress = state.progress
        if (progress != null) {
            EmojiSetInstallProgress(state, onCancel)
            return@Column
        }
        val installed = set.installed
        Row(horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
            val wide = Modifier.weight(1f)
            when {
                installed == null ->
                    WhiteNoiseButton(
                        onClick = onInstall,
                        enabled = !state.busy,
                        modifier =
                            wide.explain(R.string.emoji_sets_install, R.string.explanation_organize_emoji_install),
                    ) { Text(stringResource(R.string.emoji_sets_install)) }
                installed.updateAvailable ->
                    WhiteNoiseButton(
                        onClick = onInstall,
                        enabled = !state.busy,
                        modifier = wide.explain(R.string.emoji_sets_update, R.string.explanation_organize_emoji_update),
                    ) { Text(stringResource(R.string.emoji_sets_update)) }
                else ->
                    WhiteNoiseOutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = !state.busy && !state.discovering,
                        modifier =
                            wide.explain(
                                R.string.emoji_sets_check_update,
                                R.string.explanation_organize_emoji_check_update,
                            ),
                    ) { Text(stringResource(R.string.emoji_sets_check_update)) }
            }
            if (installed != null) EmojiSetRemoveButton(set, enabled = !state.busy, onRemove = onRemove)
        }
    }
}

/**
 * Remove for an installed set, error-coloured while it can run. It confirms first (#4097), because getting the set
 * back means downloading it again, which is not an Undo.
 */
@Composable
private fun EmojiSetRemoveButton(
    set: EmojiSetFfi,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    var confirmRemove by remember(set.coordinate) { mutableStateOf(false) }
    if (confirmRemove) {
        // Removal deletes the downloaded images, and downloading them again is not an Undo.
        ConfirmDialog(
            title = stringResource(R.string.emoji_sets_remove_title, set.displayTitle()),
            message = stringResource(R.string.emoji_sets_remove_detail),
            confirmLabel = stringResource(R.string.remove),
            destructive = true,
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
            confirmTag = "emoji_sets.remove.confirm",
            cancelTag = "emoji_sets.remove.cancel",
        )
    }
    WhiteNoiseOutlinedButton(
        onClick = { confirmRemove = true },
        enabled = enabled,
        modifier = Modifier.explain(R.string.remove, R.string.explanation_organize_emoji_remove),
    ) {
        Text(
            stringResource(R.string.remove),
            color = if (enabled) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}

/** MDK's measured install progress with its Cancel action. */
@Composable
private fun EmojiSetInstallProgress(
    state: EmojiSetsState,
    onCancel: () -> Unit,
) {
    val progress = state.progress ?: return
    if (progress.totalEmoji > 0u) {
        LinearProgressIndicator(
            progress = { progress.completedEmoji.toFloat() / progress.totalEmoji.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (progress.totalEmoji == 0u) {
                stringResource(R.string.emoji_sets_preparing_install)
            } else {
                stringResource(
                    R.string.emoji_sets_install_progress,
                    progress.completedEmoji.toInt(),
                    progress.totalEmoji.toInt(),
                )
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onCancel,
            enabled = !state.cancelling,
            modifier = Modifier.explain(R.string.cancel, R.string.explanation_organize_emoji_cancel),
        ) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun EmojiSetLoadAction(
    enabled: Boolean,
    onLoad: () -> Unit,
) {
    WhiteNoiseFilledTonalButton(
        onClick = onLoad,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin)
                .testTag(EMOJI_SET_LOAD_TEST_TAG)
                .explain(R.string.emoji_sets_load_emoji, R.string.explanation_organize_emoji_load_emoji),
    ) {
        Icon(
            painterResource(R.drawable.ic_download),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.emoji_sets_load_emoji))
    }
}

/** Names the image hosts, so the user knows who would see their IP address before any image loads. */
@Composable
private fun EmojiSetHostDisclosure(set: EmojiSetFfi) {
    val hosts =
        remember(set) {
            (listOfNotNull(set.imageUrl) + set.emoji.map { it.url })
                .mapNotNull {
                    val host = Uri.parse(it).host?.trim()
                    host?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)
                }.distinct()
        }
    val text =
        if (hosts.isEmpty()) {
            stringResource(R.string.emoji_sets_image_hosts_unknown)
        } else {
            val shown = hosts.take(MAX_NAMED_HOSTS)
            val more = hosts.size - shown.size
            val names =
                if (more > 0) shown + pluralStringResource(R.plurals.emoji_sets_more_hosts, more, more) else shown
            val formattedHosts = ListFormatter.getInstance().format(names)
            pluralStringResource(R.plurals.emoji_sets_image_hosts, hosts.size, formattedHosts)
        }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Adds the long-press explanation for an action titled [title]. */
@Composable
private fun Modifier.explain(
    title: Int,
    body: Int,
): Modifier = explainOnLongPress(Explanation(stringResource(title), stringResource(body)))
