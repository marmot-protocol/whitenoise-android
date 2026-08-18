package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

internal const val GROUP_EMOJI_IMAGE_SIZE_PX = 512
internal const val GROUP_EMOJI_IMAGE_MAX_SELECTION = 2

internal sealed class GroupEmojiImageException : Exception() {
    data object InvalidSelection : GroupEmojiImageException()

    data object UnsupportedGlyph : GroupEmojiImageException()

    data object EncodeFailed : GroupEmojiImageException()
}

internal data class GroupEmojiSelectionUpdate(
    val emojis: List<String>,
    val limitReached: Boolean,
)

internal fun addGroupEmojiSelection(
    current: List<String>,
    emoji: String,
): GroupEmojiSelectionUpdate {
    if (emoji.isBlank()) return GroupEmojiSelectionUpdate(current, limitReached = false)
    if (current.size >= GROUP_EMOJI_IMAGE_MAX_SELECTION) {
        return GroupEmojiSelectionUpdate(current, limitReached = true)
    }
    return GroupEmojiSelectionUpdate(current + emoji, limitReached = false)
}

/**
 * Renders catalog-selected emoji into the exact opaque JPEG bytes submitted to
 * MDK's existing encrypted group-image API. Emoji are never persisted as text,
 * so recipients do not re-render them with a different platform font.
 */
internal object GroupEmojiImageRenderer {
    private const val JPEG_QUALITY = 92
    private const val ONE_EMOJI_TEXT_SIZE_PX = 300f
    private const val TWO_EMOJI_TEXT_SIZE_PX = 210f
    private const val TWO_EMOJI_FIRST_CENTER_X = 150f
    private const val TWO_EMOJI_SECOND_CENTER_X = 362f
    private const val BACKGROUND_COLOR = 0xfff1f3f5.toInt()

    fun render(
        emojis: List<String>,
        hasGlyph: (Paint, String) -> Boolean = { paint, emoji -> paint.hasGlyph(emoji) },
    ): ImageUploadDraft {
        if (emojis.size !in 1..GROUP_EMOJI_IMAGE_MAX_SELECTION || emojis.any(String::isBlank)) {
            throw GroupEmojiImageException.InvalidSelection
        }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
            }
        if (emojis.any { !hasGlyph(paint, it) }) throw GroupEmojiImageException.UnsupportedGlyph

        val bitmap = Bitmap.createBitmap(GROUP_EMOJI_IMAGE_SIZE_PX, GROUP_EMOJI_IMAGE_SIZE_PX, Bitmap.Config.ARGB_8888)
        val encoded =
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(BACKGROUND_COLOR)
                when (emojis.size) {
                    1 -> drawCentered(canvas, paint, emojis.single(), GROUP_EMOJI_IMAGE_SIZE_PX / 2f, ONE_EMOJI_TEXT_SIZE_PX)
                    else -> {
                        drawCentered(canvas, paint, emojis[0], TWO_EMOJI_FIRST_CENTER_X, TWO_EMOJI_TEXT_SIZE_PX)
                        drawCentered(canvas, paint, emojis[1], TWO_EMOJI_SECOND_CENTER_X, TWO_EMOJI_TEXT_SIZE_PX)
                    }
                }
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        throw GroupEmojiImageException.EncodeFailed
                    }
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        if (encoded.isEmpty()) throw GroupEmojiImageException.EncodeFailed
        return GroupImageDraftProcessor.fromBytes(encoded, sourceUrl = null)
    }

    private fun drawCentered(
        canvas: Canvas,
        paint: Paint,
        emoji: String,
        centerX: Float,
        textSize: Float,
    ) {
        paint.textSize = textSize
        paint.color = Color.BLACK
        val metrics = paint.fontMetrics
        val baseline = GROUP_EMOJI_IMAGE_SIZE_PX / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, centerX, baseline, paint)
    }
}
