package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/** Side of the square EmojiGlyph artwork draws in: the Unicode glyph's em box, capped by its cell. */
internal fun Density.emojiGlyphArtworkSize(
    fontSize: TextUnit,
    cell: Dp,
): Dp = fontSize.toDp().coerceAtMost(cell)
