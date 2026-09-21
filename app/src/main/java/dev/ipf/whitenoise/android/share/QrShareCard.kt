package dev.ipf.whitenoise.android.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.PathParser
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.QrCodeEncoder
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Fixed export data. Only fields intentionally visible on the PNG belong here. */
internal data class QrShareCardSpec(
    val headline: String,
    val qrPayload: String,
    val displayName: String? = null,
    val avatar: Bitmap? = null,
)

/** A generated PNG exposed through the existing bounded FileProvider share cache. */
internal data class StagedQrShareCard(
    val stream: OutboundShareStream,
    val file: File,
)

/**
 * Renders the same fixed 480 x 540 point card used by iOS at 3x density. The bitmap is always light and opaque so
 * device theme and font scale cannot change the exported pixels or reduce QR contrast.
 */
@Suppress("MagicNumber", "MaxLineLength") // Fixed export geometry and the reviewed vector path are intentional literals.
internal object QrShareCardRenderer {
    const val WIDTH_PX = 1_440
    const val HEIGHT_PX = 1_620
    private const val QR_SIZE_PX = 900
    private const val QR_SURFACE_SIZE_PX = 972
    private const val PROFILE_ROW_HEIGHT_PX = 180
    private const val AVATAR_SIZE_PX = 132
    private const val PROFILE_GAP_PX = 42f
    private const val PROFILE_TEXT_MAX_WIDTH_PX = 900f

    /** Render a metadata-free ARGB bitmap containing only [spec]'s visible fields and the White Noise mark. */
    fun render(spec: QrShareCardSpec): Bitmap {
        require(spec.headline.isNotBlank()) { "Share-card headline cannot be blank" }
        require(spec.qrPayload.isNotBlank()) { "Share-card QR payload cannot be blank" }

        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 245, 245))

        drawText(
            canvas = canvas,
            text = spec.headline,
            bounds = RectF(96f, 36f, WIDTH_PX - 96f, 198f),
            textSize = 69f,
            color = Color.rgb(92, 92, 92),
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD),
            alignment = Layout.Alignment.ALIGN_CENTER,
            maxLines = 2,
        )

        val hasProfile = !spec.displayName.isNullOrBlank()
        val qrTop = if (hasProfile) 390f else 282f
        if (hasProfile) drawProfileRow(canvas, spec)
        drawQr(canvas, spec.qrPayload, qrTop)
        drawBranding(canvas)
        return bitmap
    }

    /** Encode and stage one generated card without adding EXIF, text chunks, or account-derived file names. */
    suspend fun stage(
        context: Context,
        spec: QrShareCardSpec,
    ): StagedQrShareCard =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
            check(directory.isDirectory) { "Share cache is unavailable" }
            val file = File.createTempFile("profile_card_", ".png", directory)
            var completed = false
            try {
                val rendered = render(spec)
                try {
                    file.outputStream().buffered().use { output ->
                        if (!rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw IOException("Could not encode share card")
                        }
                    }
                } finally {
                    rendered.recycle()
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                completed = true
                StagedQrShareCard(OutboundShareStream(uri, "image/png"), file)
            } finally {
                if (!completed) file.delete()
            }
        }

    /** Draws the optional public display name and avatar without changing QR placement or contents. */
    private fun drawProfileRow(
        canvas: Canvas,
        spec: QrShareCardSpec,
    ) {
        val rowTop = 204f
        val typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        val displayName = checkNotNull(spec.displayName)
        val textWidth =
            Paint(Paint.ANTI_ALIAS_FLAG)
                .apply {
                    textSize = 84f
                    this.typeface = typeface
                }.measureText(displayName)
                .coerceIn(1f, PROFILE_TEXT_MAX_WIDTH_PX)
        val contentWidth = AVATAR_SIZE_PX + PROFILE_GAP_PX + textWidth
        val avatarLeft = (WIDTH_PX - contentWidth) / 2f
        val avatarTop = rowTop + (PROFILE_ROW_HEIGHT_PX - AVATAR_SIZE_PX) / 2f
        val textLeft = avatarLeft + AVATAR_SIZE_PX + PROFILE_GAP_PX
        drawAvatar(canvas, spec, avatarLeft, avatarTop)
        drawText(
            canvas = canvas,
            text = displayName,
            bounds = RectF(textLeft, rowTop, textLeft + textWidth, rowTop + PROFILE_ROW_HEIGHT_PX),
            textSize = 84f,
            color = Color.BLACK,
            typeface = typeface,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            maxLines = 2,
        )
    }

    /** Draws a center-cropped avatar or deterministic initials derived only from visible profile data. */
    private fun drawAvatar(
        canvas: Canvas,
        spec: QrShareCardSpec,
        left: Float,
        top: Float,
    ) {
        val centerX = left + AVATAR_SIZE_PX / 2f
        val centerY = top + AVATAR_SIZE_PX / 2f
        val radius = AVATAR_SIZE_PX / 2f
        val avatar = spec.avatar
        if (avatar != null && !avatar.isRecycled) {
            val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = AVATAR_SIZE_PX / minOf(avatar.width, avatar.height).toFloat()
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(
                left - (avatar.width * scale - AVATAR_SIZE_PX) / 2f,
                top - (avatar.height * scale - AVATAR_SIZE_PX) / 2f,
            )
            shader.setLocalMatrix(matrix)
            canvas.drawCircle(centerX, centerY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            return
        }

        val palette = intArrayOf(0xFF006A6A.toInt(), 0xFF8C4A00.toInt(), 0xFF5B5FC7.toInt(), 0xFF006D3B.toInt(), 0xFF9A4055.toInt())
        val paletteIndex = Math.floorMod(spec.displayName.orEmpty().hashCode(), palette.size)
        canvas.drawCircle(centerX, centerY, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[paletteIndex] })
        val initials = IdentityFormatter.initials(spec.displayName.orEmpty())
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = 58f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(initials, centerX, baseline, paint)
    }

    /** Draws the canonical payload with a four-module quiet zone on a high-contrast white surface. */
    private fun drawQr(
        canvas: Canvas,
        payload: String,
        top: Float,
    ) {
        val left = (WIDTH_PX - QR_SURFACE_SIZE_PX) / 2f
        val surface = RectF(left, top, left + QR_SURFACE_SIZE_PX, top + QR_SURFACE_SIZE_PX)
        canvas.drawRoundRect(surface, 72f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val qrPixels =
            QrCodeEncoder.pixels(
                content = payload,
                size = QR_SIZE_PX,
                onColor = Color.BLACK,
                offColor = Color.WHITE,
                marginModules = 4,
            )
        val qr = Bitmap.createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.ARGB_8888)
        qr.setPixels(qrPixels, 0, QR_SIZE_PX, 0, 0, QR_SIZE_PX, QR_SIZE_PX)
        canvas.drawBitmap(qr, left + 36f, top + 36f, Paint().apply { isFilterBitmap = false })
        qr.recycle()
    }

    /** Draws the public White Noise app mark and website footer. */
    private fun drawBranding(canvas: Canvas) {
        val markSize = 132f
        val markPath = checkNotNull(PathParser.createPathFromPathData(MARK_PATH_DATA)).apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
        canvas.save()
        canvas.translate((WIDTH_PX - markSize) / 2f, 1_380f)
        canvas.scale(markSize / MARK_VIEWPORT, markSize / MARK_VIEWPORT)
        canvas.drawPath(
            markPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.FILL
            },
        )
        canvas.restore()
        drawText(
            canvas = canvas,
            text = "whitenoise.chat",
            bounds = RectF(96f, 1_518f, WIDTH_PX - 96f, 1_602f),
            textSize = 42f,
            color = Color.rgb(92, 92, 92),
            typeface = android.graphics.Typeface.DEFAULT,
            alignment = Layout.Alignment.ALIGN_CENTER,
            maxLines = 1,
        )
    }

    private const val MARK_VIEWPORT = 1_024f
    private const val MARK_PATH_DATA =
        "M174.057,771.956V252.044H348.115V411.296L424.971,252.044H599.029V411.296L675.885,252.044H849.943V771.956H675.885V612.704L599.029,771.956H424.971V612.704L348.115,771.956H174.057ZM320.989,279.17H201.183V715.749L320.989,467.503V279.17ZM703.011,744.83H822.817V308.251L703.011,556.497V744.83ZM582,744.83L806.732,279.17H692.914L468.182,744.83H582ZM555.818,279.17H442L217.268,744.83H331.086L555.818,279.17ZM571.903,308.251L452.097,556.497V715.749L571.903,467.503V308.251Z"

    /** Centers bounded, ellipsized text while preserving the export's fixed pixel geometry. */
    private fun drawText(
        canvas: Canvas,
        text: String,
        bounds: RectF,
        textSize: Float,
        color: Int,
        typeface: android.graphics.Typeface,
        alignment: Layout.Alignment,
        maxLines: Int,
    ) {
        val paint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.textSize = textSize
                this.typeface = typeface
            }
        val layout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, bounds.width().toInt())
                .setAlignment(alignment)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        canvas.save()
        canvas.translate(bounds.left, bounds.top + ((bounds.height() - layout.height) / 2f).coerceAtLeast(0f))
        layout.draw(canvas)
        canvas.restore()
    }
}
