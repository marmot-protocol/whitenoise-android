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

internal data class TwoEmojiLayout(
    val textSize: Float,
    val firstCenterX: Float,
    val secondCenterX: Float,
)

internal fun addGroupEmojiSelection(
    current: List<String>,
    emoji: String,
): GroupEmojiSelectionUpdate =
    when {
        emoji.isBlank() -> GroupEmojiSelectionUpdate(current, limitReached = false)
        current.size >= GROUP_EMOJI_IMAGE_MAX_SELECTION -> GroupEmojiSelectionUpdate(current, limitReached = true)
        else -> GroupEmojiSelectionUpdate(current + emoji, limitReached = false)
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

    // Wide glyphs at the base text size can exceed fixed slot centers, so the
    // two-emoji layout measures each glyph's advance and enforces this gap,
    // shrinking the text size when the measured pair would not fit.
    private const val TWO_EMOJI_GAP_PX = 32f
    private const val TWO_EMOJI_EDGE_MARGIN_PX = 28f

    // A single dark neutral rather than a theme-derived color — the avatar is
    // a persisted image shared with every member, so it must not vary with the
    // creator's theme, and a dark tone reads well on both light and dark UIs.
    private const val BACKGROUND_COLOR = 0xff3c4043.toInt()

    fun render(
        emojis: List<String>,
        hasGlyph: (Paint, String) -> Boolean = { paint, emoji -> paint.hasGlyph(emoji) },
    ): ImageUploadDraft {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
            }
        requireRenderable(emojis, paint, hasGlyph)

        val bitmap = Bitmap.createBitmap(GROUP_EMOJI_IMAGE_SIZE_PX, GROUP_EMOJI_IMAGE_SIZE_PX, Bitmap.Config.ARGB_8888)
        val encoded =
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(BACKGROUND_COLOR)
                when (emojis.size) {
                    1 ->
                        drawCentered(
                            canvas,
                            paint,
                            emojis.single(),
                            GROUP_EMOJI_IMAGE_SIZE_PX / 2f,
                            ONE_EMOJI_TEXT_SIZE_PX,
                        )
                    else -> drawSideBySide(canvas, paint, emojis[0], emojis[1])
                }
                encodeJpeg(bitmap)
            } finally {
                bitmap.recycle()
            }
        return GroupImageDraftProcessor.fromBytes(encoded, sourceUrl = null)
    }

    private fun requireRenderable(
        emojis: List<String>,
        paint: Paint,
        hasGlyph: (Paint, String) -> Boolean,
    ) {
        if (emojis.size !in 1..GROUP_EMOJI_IMAGE_MAX_SELECTION || emojis.any(String::isBlank)) {
            throw GroupEmojiImageException.InvalidSelection
        }
        if (emojis.any { !hasGlyph(paint, it) }) throw GroupEmojiImageException.UnsupportedGlyph
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw GroupEmojiImageException.EncodeFailed
            }
            val encoded = output.toByteArray()
            if (encoded.isEmpty()) throw GroupEmojiImageException.EncodeFailed
            encoded
        }

    private fun drawSideBySide(
        canvas: Canvas,
        paint: Paint,
        first: String,
        second: String,
    ) {
        val layout = twoEmojiLayout(paint, first, second)
        drawCentered(canvas, paint, first, layout.firstCenterX, layout.textSize)
        drawCentered(canvas, paint, second, layout.secondCenterX, layout.textSize)
    }

    internal fun twoEmojiLayout(
        paint: Paint,
        first: String,
        second: String,
    ): TwoEmojiLayout {
        paint.textSize = TWO_EMOJI_TEXT_SIZE_PX
        val usableWidth = GROUP_EMOJI_IMAGE_SIZE_PX - 2 * TWO_EMOJI_EDGE_MARGIN_PX
        val measured = paint.measureText(first) + paint.measureText(second)
        if (measured + TWO_EMOJI_GAP_PX > usableWidth) {
            paint.textSize = TWO_EMOJI_TEXT_SIZE_PX * (usableWidth - TWO_EMOJI_GAP_PX) / measured
        }
        val firstWidth = paint.measureText(first)
        val secondWidth = paint.measureText(second)
        val contentWidth = firstWidth + TWO_EMOJI_GAP_PX + secondWidth
        val left = (GROUP_EMOJI_IMAGE_SIZE_PX - contentWidth) / 2f
        return TwoEmojiLayout(
            textSize = paint.textSize,
            firstCenterX = left + firstWidth / 2f,
            secondCenterX = left + firstWidth + TWO_EMOJI_GAP_PX + secondWidth / 2f,
        )
    }

    private fun drawCentered(
        canvas: Canvas,
        paint: Paint,
        emoji: String,
        centerX: Float,
        textSize: Float,
    ) {
        paint.textSize = textSize
        // Color emoji ignore the paint color; this only affects glyphs that
        // fall back to text presentation, which need contrast on the dark
        // background.
        paint.color = Color.WHITE
        val metrics = paint.fontMetrics
        val baseline = GROUP_EMOJI_IMAGE_SIZE_PX / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(emoji, centerX, baseline, paint)
    }
}
