@file:Suppress("FunctionNaming")
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ipf.whitenoise.android.ui.settings

import android.icu.text.ListFormatter
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.EmojiSetEmojiFfi
import dev.ipf.marmotkit.EmojiSetFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.EmojiSetsState
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseFilledTonalButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import java.util.Locale

/** The set preview's Load emoji action. */
internal const val EMOJI_SET_LOAD_TEST_TAG = "emoji_sets.load"

/** Every tile of the set preview's grid, keyed by shortcode. */
internal fun emojiSetTileTestTag(shortcode: String) = "emoji_sets.tile.$shortcode"

private val CoverSize = 72.dp
private val CoverArtSize = 56.dp
private val TileMinSize = 56.dp
private val TileGap = 8.dp
private const val TILE_ART_FRACTION = 0.72f
private const val PULSE_LOW_ALPHA = 0.35f
private const val PULSE_MILLIS = 800
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
            if (images != null && !images.requested && rows.isNotEmpty() && state.progress == null) {
                item(key = "set.load") {
                    EmojiSetLoadAction(enabled = !state.busy, onLoad = images::request)
                }
            }
            item(key = "set.actions") { EmojiSetActions(state, set, onInstall, onCancel, onRemove, onCheckUpdate) }
            item(key = "set.status") { EmojiSetStatus(state) }
            if (images?.requested == true) {
                rows.forEachIndexed { index, row ->
                    item(key = "set.row.$index") { EmojiSetTileRow(row, columns, images, pulse) }
                }
            }
        }
    }
}

/** How many tiles fit across the screen margins at [TileMinSize]. */
private fun gridColumns(width: Dp): Int {
    val usable = width - WhiteNoiseSpacing.CompactScreenMargin * 2 + TileGap
    return (usable / (TileMinSize + TileGap)).toInt().coerceAtLeast(1)
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

/** Install, update, check or remove; during an install, only its measured progress and Cancel. */
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
            if (installed != null) {
                WhiteNoiseOutlinedButton(
                    onClick = onRemove,
                    enabled = !state.busy,
                    modifier = Modifier.explain(R.string.remove, R.string.explanation_organize_emoji_remove),
                ) {
                    Text(
                        stringResource(R.string.remove),
                        color = if (state.busy) Color.Unspecified else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
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
                .mapNotNull { Uri.parse(it).host?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }
                .distinct()
        }
    val text =
        if (hosts.isEmpty()) {
            stringResource(R.string.emoji_sets_image_hosts_unknown)
        } else {
            val shown = hosts.take(MAX_NAMED_HOSTS)
            val more = hosts.size - shown.size
            val names =
                if (more > 0) shown + pluralStringResource(R.plurals.emoji_sets_more_hosts, more, more) else shown
            pluralStringResource(R.plurals.emoji_sets_image_hosts, hosts.size, ListFormatter.getInstance().format(names))
        }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One row of the grid; a short last row keeps tile widths by padding with empty cells. */
@Composable
private fun EmojiSetTileRow(
    row: List<EmojiSetEmojiFfi>,
    columns: Int,
    images: EmojiSetImages,
    pulse: State<Float>,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin),
        horizontalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        row.forEach { emoji -> EmojiSetTile(emoji.shortcode, images, pulse, Modifier.weight(1f)) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * The emoji itself, a pulsing blank while it loads, or a retry icon. Loaded art exposes its shortcode on long press.
 */
@Composable
private fun EmojiSetTile(
    shortcode: String,
    images: EmojiSetImages,
    pulse: State<Float>,
    modifier: Modifier,
) {
    val image = images[shortcode]
    DisposableEffect(images, shortcode) {
        images.attach(shortcode)
        onDispose { images.detach(shortcode) }
    }
    // Keyed on the attempt, not the tile state, which the load itself changes.
    LaunchedEffect(images, shortcode, images.requested, images.attempt(shortcode)) { images.load(shortcode) }
    val label = ":$shortcode:"
    val canRetry = image == EmojiSetImage.Failed && images.retryable
    val description =
        when {
            image is EmojiSetImage.Ready -> label
            canRetry -> stringResource(R.string.emoji_sets_emoji_failed, label)
            image == EmojiSetImage.Failed -> stringResource(R.string.emoji_sets_emoji_unavailable, label)
            else -> stringResource(R.string.emoji_sets_emoji_loading, label)
        }
    // The row's weight goes on a direct child; TooltipBox would hand it to its inner anchor.
    Box(modifier) {
        EmojiSetTileContent(shortcode, image, label, description, canRetry, pulse) { images.retry(shortcode) }
    }
}

/** The tile's square and its long-press tooltip. */
@Suppress("LongParameterList")
@Composable
private fun EmojiSetTileContent(
    shortcode: String,
    image: EmojiSetImage?,
    label: String,
    description: String,
    canRetry: Boolean,
    pulse: State<Float>,
    onRetry: () -> Unit,
) {
    val tile: @Composable () -> Unit = {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .then(
                        if (canRetry) {
                            Modifier.clickable(
                                onClickLabel = stringResource(R.string.retry),
                                role = Role.Button,
                                onClick = onRetry,
                            )
                        } else {
                            Modifier
                        },
                    ).semantics { contentDescription = description }
                    .testTag(emojiSetTileTestTag(shortcode)),
            contentAlignment = Alignment.Center,
        ) {
            when (image) {
                is EmojiSetImage.Ready ->
                    EmojiArtImage(image.art, contentDescription = null, Modifier.fillMaxSize(TILE_ART_FRACTION))
                EmojiSetImage.Failed ->
                    Icon(
                        painterResource(if (canRetry) R.drawable.ic_refresh else R.drawable.ic_error),
                        contentDescription = null,
                        tint =
                            if (canRetry) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                EmojiSetImage.Loading, null -> PulsingBlank(pulse)
            }
        }
    }
    if (image is EmojiSetImage.Ready) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            content = tile,
        )
    } else {
        tile()
    }
}

/** One pulse shared by every loading tile, so they breathe together; it holds still when animations are off. */
@Composable
private fun rememberTilePulse(): State<Float> =
    if (rememberSystemAnimationsEnabled()) {
        rememberInfiniteTransition(label = "emoji-tile")
            .animateFloat(
                initialValue = 1f,
                targetValue = PULSE_LOW_ALPHA,
                animationSpec = infiniteRepeatable(tween(PULSE_MILLIS, easing = LinearEasing), RepeatMode.Reverse),
                label = "emoji-tile-alpha",
            )
    } else {
        remember { mutableFloatStateOf(1f) }
    }

private val StillPulse: State<Float> = mutableFloatStateOf(1f)

/** The loading placeholder. The pulse is read while drawing, so it never recomposes the grid. */
@Composable
private fun PulsingBlank(pulse: State<Float>) {
    Box(
        Modifier
            .fillMaxSize(TILE_ART_FRACTION)
            .graphicsLayer { alpha = pulse.value }
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

/** Adds the long-press explanation for an action titled [title]. */
@Composable
private fun Modifier.explain(
    title: Int,
    body: Int,
): Modifier = explainOnLongPress(Explanation(stringResource(title), stringResource(body)))
