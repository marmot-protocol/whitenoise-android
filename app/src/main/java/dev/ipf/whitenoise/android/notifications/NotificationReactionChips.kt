package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Use a common SystemUI chip template for available artwork and Unicode. A missing or
 * unreadable custom image keeps its original text choice, as does a failed preparation.
 */
internal suspend fun notificationReactionChipArtwork(
    context: Context,
    choices: List<String>,
): Map<String, Bitmap> {
    if (choices.none(EmojiShortcodes::isShortcode)) return emptyMap()
    return try {
        withTimeoutOrNull(NOTIFICATION_EMOJI_PREPARE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val budget = EmojiReadBudget()
                val custom =
                    choices.filter(EmojiShortcodes::isShortcode).mapNotNull { code ->
                        notificationEmojiBitmap(context, code, budget)?.let { source ->
                            try {
                                code to alignedReactionChip(context, source)
                            } finally {
                                source.recycle()
                            }
                        }
                    }
                if (custom.isEmpty()) {
                    return@withContext emptyMap()
                }
                val unicode =
                    choices.filterNot(EmojiShortcodes::isShortcode).map { emoji ->
                        val source = unicodeReactionChip(emoji)
                        try {
                            emoji to alignedReactionChip(context, source)
                        } finally {
                            source.recycle()
                        }
                    }
                (custom + unicode).toMap()
            }
        }.orEmpty()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (
        @Suppress("TooGenericExceptionCaught")
        _: Exception,
    ) {
        emptyMap()
    } catch (_: OutOfMemoryError) {
        emptyMap()
    }
}

private fun unicodeReactionChip(emoji: String): Bitmap {
    val tile = Bitmap.createBitmap(CHIP_TILE_PX, CHIP_TILE_PX, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = CHIP_SOURCE_TEXT_PX }
    val bounds = Rect()
    paint.getTextBounds(emoji, 0, emoji.length, bounds)
    if (bounds.isEmpty) return tile
    val canvas = Canvas(tile)
    val scale = CHIP_TILE_PX.toFloat() / maxOf(bounds.width(), bounds.height())
    canvas.translate(CHIP_TILE_CENTER_PX, CHIP_TILE_CENTER_PX)
    canvas.scale(scale, scale)
    canvas.drawText(emoji, -bounds.exactCenterX(), -bounds.exactCenterY(), paint)
    return tile
}

private fun alignedReactionChip(
    context: Context,
    source: Bitmap,
): Bitmap {
    val tile = Bitmap.createBitmap(CHIP_TILE_PX, CHIP_TILE_PX, Bitmap.Config.ARGB_8888)
    // The contextual template reserves a gap between its start drawable and its title
    // even at zero text size. Center the visible artwork in the chip, toward the title.
    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val centerX = CHIP_TILE_CENTER_PX + if (rtl) -CHIP_CENTER_OFFSET_PX else CHIP_CENTER_OFFSET_PX
    val scale = CHIP_ART_PX / maxOf(source.width, source.height)
    val halfWidth = source.width * scale / 2
    val halfHeight = source.height * scale / 2
    val destination =
        RectF(
            centerX - halfWidth,
            CHIP_TILE_CENTER_PX - halfHeight,
            centerX + halfWidth,
            CHIP_TILE_CENTER_PX + halfHeight,
        )
    Canvas(tile).drawBitmap(source, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
    return tile
}

private const val CHIP_TILE_PX = 64
private const val CHIP_TILE_CENTER_PX = CHIP_TILE_PX / 2f
private const val CHIP_SOURCE_TEXT_PX = 48f
private const val CHIP_CENTER_OFFSET_PX = CHIP_TILE_PX * 0.14f
private const val CHIP_ART_PX = CHIP_TILE_PX - 2f * CHIP_CENTER_OFFSET_PX
