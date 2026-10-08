package dev.ipf.whitenoise.android.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.caverock.androidsvg.SVG
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Local preparation only: MDK receives ordinary raster bytes, never an SVG or an external resource. */
internal object GroupSvgRasterizer {
    private const val MAX_EDGE = 1024
    private const val MAX_DOCUMENT_EDGE = 8192f
    private const val PNG_COMPRESSION_QUALITY = 100

    suspend fun rasterize(
        bytes: ByteArray,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ByteArray =
        withContext(dispatcher) {
            runCatchingCancellable {
                currentCoroutineContext().ensureActive()
                val xml = GroupSvgPolicy.validate(bytes)
                // Defence in depth; the policy already refuses declarations before either parser runs.
                SVG.setInternalEntitiesEnabled(false)
                val svg = SVG.getFromString(xml)
                val viewBox = svg.documentViewBox
                if (viewBox != null) {
                    require(
                        hasPositiveFiniteSize(viewBox.width(), viewBox.height()),
                    )
                }
                val width = svg.documentWidth.takeIf { it >= 0f } ?: viewBox?.width() ?: MAX_EDGE.toFloat()
                val height = svg.documentHeight.takeIf { it >= 0f } ?: viewBox?.height() ?: MAX_EDGE.toFloat()
                require(
                    hasPositiveFiniteSize(width, height) &&
                        width in 1f..MAX_DOCUMENT_EDGE &&
                        height in 1f..MAX_DOCUMENT_EDGE,
                )
                if (viewBox == null) svg.setDocumentViewBox(0f, 0f, width, height)
                val scale = MAX_EDGE / maxOf(width, height)
                val bitmap =
                    Bitmap.createBitmap(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                try {
                    currentCoroutineContext().ensureActive()
                    svg.setDocumentWidth("100%")
                    svg.setDocumentHeight("100%")
                    svg.renderToCanvas(Canvas(bitmap))
                    currentCoroutineContext().ensureActive()
                    val output = ByteArrayOutputStream()
                    require(bitmap.compress(Bitmap.CompressFormat.PNG, PNG_COMPRESSION_QUALITY, output))
                    require(output.size() <= REMOTE_PROFILE_IMAGE_MAX_BYTES)
                    output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }.getOrElse { error ->
                if (error !is Exception) throw error
                throw ImageUploadPreparationException.UnsupportedSvg
            }
        }

    private fun hasPositiveFiniteSize(
        width: Float,
        height: Float,
    ): Boolean = width.isFinite() && height.isFinite() && width > 0f && height > 0f
}

/** Raster files retain their crop source; SVGs are converted once before preview and final cropping. */
internal suspend fun readGroupIdentityImageSource(
    resolver: ContentResolver,
    uri: Uri,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): ByteArray {
    val source = readIdentityImageSource(resolver, uri)
    val mime = withContext(ioDispatcher) { resolver.getType(uri) }
    return prepareGroupIdentityImageSource(source, mime)
}

/** Sniff only a small prefix so raster fallback never needs to read a large photo into memory. */
internal suspend fun isGroupSvgDocument(
    resolver: ContentResolver,
    uri: Uri,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean =
    withContext(ioDispatcher) {
        val mime = resolver.getType(uri)
        if (isSvgMime(mime)) return@withContext true
        resolver.openInputStream(uri)?.use { stream ->
            val prefix = ByteArray(SVG_PREFIX_BYTES)
            var size = 0
            while (size < prefix.size) {
                val count = stream.read(prefix, size, prefix.size - size)
                if (count <= 0) break
                size += count
            }
            looksLikeGroupSvg(mime, prefix.copyOf(size))
        } ?: false
    }

private const val SVG_PREFIX_BYTES = 128

private fun isSvgMime(mime: String?): Boolean =
    mime
        ?.substringBefore(';')
        ?.trim()
        ?.equals("image/svg+xml", ignoreCase = true) == true

/** Detects candidate XML/SVG bytes; the rasterizer still validates content before any crop is offered. */
private fun looksLikeGroupSvg(
    mime: String?,
    source: ByteArray,
): Boolean =
    isSvgMime(mime) ||
        source
            .take(SVG_PREFIX_BYTES)
            .toByteArray()
            .toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
            .startsWith("<")

/** Picker and decrypted viewer inputs share SVG detection, validation and rasterization. */
internal suspend fun prepareGroupIdentityImageSource(
    source: ByteArray,
    mime: String?,
): ByteArray = if (looksLikeGroupSvg(mime, source)) GroupSvgRasterizer.rasterize(source) else source
