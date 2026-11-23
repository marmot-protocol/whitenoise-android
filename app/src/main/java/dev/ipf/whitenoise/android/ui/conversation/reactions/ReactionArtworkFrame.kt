package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import dev.ipf.whitenoise.android.ui.conversation.composer.emojiGlyphArtworkSize
import kotlin.math.roundToInt

/** Matches Image's Fit behavior without stretching decoded rectangular artwork. */
internal fun reactionArtworkFit(
    intrinsic: Size,
    container: Size,
): Size =
    if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
        val scale = ContentScale.Fit.computeScaleFactor(intrinsic, container)
        Size(intrinsic.width * scale.scaleX, intrinsic.height * scale.scaleY)
    } else {
        container
    }

/** Image centers the fitted float drawing size using rounded integer alignment. */
internal fun reactionArtworkFrame(
    container: Rect,
    intrinsic: Size,
    direction: LayoutDirection,
): Rect {
    val fit = reactionArtworkFit(intrinsic, container.size)
    val offset =
        Alignment.Center.align(
            IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            IntSize(container.width.roundToInt(), container.height.roundToInt()),
            direction,
        )
    return Rect(container.topLeft + Offset(offset.x.toFloat(), offset.y.toFloat()), fit)
}

/** The centered square where EmojiGlyph draws artwork inside [cell], so a flight departs from its rendered pixels. */
internal fun Density.reactionGlyphArtwork(
    cell: Rect,
    fontSize: TextUnit,
    direction: LayoutDirection,
): Rect {
    val side = emojiGlyphArtworkSize(fontSize, cell.height.toDp()).roundToPx()
    val offset =
        Alignment.Center.align(
            IntSize(side, side),
            IntSize(cell.width.roundToInt(), cell.height.roundToInt()),
            direction,
        )
    return Rect(cell.topLeft + Offset(offset.x.toFloat(), offset.y.toFloat()), Size(side.toFloat(), side.toFloat()))
}
