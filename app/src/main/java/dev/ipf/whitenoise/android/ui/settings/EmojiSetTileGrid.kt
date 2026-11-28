@file:Suppress("FunctionNaming")
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.ipf.whitenoise.android.ui.settings

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.EmojiSetEmojiFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiArtImage
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Every tile of the set preview's grid, keyed by shortcode. */
internal fun emojiSetTileTestTag(shortcode: String) = "emoji_sets.tile.$shortcode"

private val TileMinSize = 56.dp
private val TileGap = 8.dp
private const val TILE_ART_FRACTION = 0.72f
private const val PULSE_LOW_ALPHA = 0.35f
private const val PULSE_MILLIS = 800

/** How many tiles fit across the screen margins at [TileMinSize]. */
internal fun gridColumns(width: Dp): Int {
    val usable = width - WhiteNoiseSpacing.CompactScreenMargin * 2 + TileGap
    return (usable / (TileMinSize + TileGap)).toInt().coerceAtLeast(1)
}

/** One row of the grid; a short last row keeps tile widths by padding with empty cells. */
@Composable
internal fun EmojiSetTileRow(
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
                EmojiSetImage.Failed -> {
                    val colors = MaterialTheme.colorScheme
                    Icon(
                        painterResource(if (canRetry) R.drawable.ic_refresh else R.drawable.ic_error),
                        contentDescription = null,
                        tint = if (canRetry) colors.error else colors.onSurfaceVariant,
                    )
                }
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
internal fun rememberTilePulse(): State<Float> =
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

internal val StillPulse: State<Float> = mutableFloatStateOf(1f)

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
