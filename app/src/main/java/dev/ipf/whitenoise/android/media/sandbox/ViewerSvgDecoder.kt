package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import dev.ipf.whitenoise.android.media.GroupSvgPolicy
import kotlin.math.ceil

/** Original vector parsing and viewport rendering remain inside the permissionless decoder. */
internal object ViewerSvgDecoder {
    @Volatile private var inProcessTestDecoding = false

    /** Explicit JVM codec fixture only; release builds can never enable privileged SVG parsing. */
    internal fun allowInProcessDecodingForTests() {
        check(dev.ipf.whitenoise.android.BuildConfig.DEBUG)
        inProcessTestDecoding = true
    }

    private fun canDecodeHere(): Boolean =
        android.os.Process.isIsolated() ||
            (dev.ipf.whitenoise.android.BuildConfig.DEBUG && inProcessTestDecoding)

    private const val MAX_DOCUMENT_EDGE = 8192f
    const val MAX_VIEWPORT_EDGE = 2048

    fun inspect(bytes: ByteArray): ImageInspection? =
        if (canDecodeHere()) {
            runCatching {
                document(bytes).info
            }.getOrNull()
        } else {
            null
        }

    /** [region] is in the inspected document's pixel coordinate system, never preview pixels. */
    fun region(
        bytes: ByteArray,
        region: ViewerSvgRegion,
        edge: Int,
    ): Bitmap? {
        if (!canDecodeHere() || edge !in 1..MAX_VIEWPORT_EDGE) return null
        val source = runCatching { document(bytes) }.getOrNull() ?: return null
        if (!region.validFor(source.info.width, source.info.height)) return null
        // A vector crop must rerender at viewport resolution even when its document region
        // is only a few units wide; raster targetDimensions deliberately never upscales.
        val ratio = edge.toDouble() / maxOf(region.width, region.height)
        val width = (region.width * ratio).toInt().coerceIn(1, edge)
        val height = (region.height * ratio).toInt().coerceIn(1, edge)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.scale((width / region.width).toFloat(), (height / region.height).toFloat())
            canvas.translate(-region.left.toFloat(), -region.top.toFloat())
            source.svg.renderToCanvas(
                canvas,
                RectF(0f, 0f, source.info.width.toFloat(), source.info.height.toFloat()),
            )
            bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun document(bytes: ByteArray): Document {
        val xml = GroupSvgPolicy.validateForViewer(bytes)
        SVG.setInternalEntitiesEnabled(false)
        val svg = xml.byteInputStream(Charsets.UTF_8).use { SVG.getFromInputStream(it) }
        val viewBox = svg.documentViewBox
        if (viewBox != null) require(validSize(viewBox.width(), viewBox.height()))
        val width = svg.documentWidth.takeIf { it > 0f } ?: viewBox?.width() ?: MAX_DOCUMENT_EDGE
        val height = svg.documentHeight.takeIf { it > 0f } ?: viewBox?.height() ?: MAX_DOCUMENT_EDGE
        require(validSize(width, height))
        if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        return Document(
            svg,
            ImageInspection(
                ceil(width.toDouble()).toInt(),
                ceil(height.toDouble()).toInt(),
                "image/svg+xml",
                1,
            ),
        )
    }

    private fun validSize(
        width: Float,
        height: Float,
    ): Boolean =
        width.isFinite() &&
            height.isFinite() &&
            width in 1f..MAX_DOCUMENT_EDGE &&
            height in 1f..MAX_DOCUMENT_EDGE

    private data class Document(
        val svg: SVG,
        val info: ImageInspection,
    )
}
