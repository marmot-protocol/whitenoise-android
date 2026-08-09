@file:Suppress("MagicNumber", "ReturnCount") // Bitmap matrices, channels, and guarded decode states are domain values.

package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import dev.ipf.whitenoise.android.media.ImageAnimationStatus
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.MediaQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class PhotoEditorSourceInfo(
    val encodedSize: EditorPixelSize,
    val orientedSize: EditorPixelSize,
    val exifOrientation: Int,
    val mediaType: String,
    val mayHaveAlpha: Boolean,
)

internal data class PhotoEditorOutputPlan(
    val quality: MediaQuality,
    val geometry: PhotoEditGeometry,
    val pipelinePlan: MediaPipeline.OutputPlan,
    val effectiveLabel: String,
)

internal enum class PhotoEditorSourceFailure {
    Empty,
    Unsupported,
    Animated,
    InvalidBounds,
    DimensionLimit,
    PixelLimit,
    AspectRatioLimit,
}

internal sealed interface PhotoEditorInspectResult {
    data class Success(
        val source: PhotoEditorSourceInfo,
    ) : PhotoEditorInspectResult

    data class Failure(
        val reason: PhotoEditorSourceFailure,
    ) : PhotoEditorInspectResult
}

internal sealed interface PhotoEditorRenderResult {
    data class Success(
        val image: MediaPipeline.FinalizedImage,
        val plan: PhotoEditorOutputPlan,
    ) : PhotoEditorRenderResult

    data class InvalidSource(
        val reason: PhotoEditorSourceFailure,
    ) : PhotoEditorRenderResult

    data object InvalidRecipe : PhotoEditorRenderResult

    data object MemoryLimit : PhotoEditorRenderResult

    data object DecodeFailed : PhotoEditorRenderResult

    data object RenderFailed : PhotoEditorRenderResult

    data object EncodeFailed : PhotoEditorRenderResult
}

internal class PhotoEditorRenderer(
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val memoryBudgetBytes: () -> Long = ::defaultEditorMemoryBudgetBytes,
    private val onEncode: () -> Unit = {},
) {
    suspend fun inspect(sourceBytes: ByteArray): PhotoEditorInspectResult =
        withContext(renderDispatcher) {
            inspectNow(sourceBytes)
        }

    suspend fun decodePreview(sourceBytes: ByteArray): Bitmap? =
        withContext(renderDispatcher) {
            when (inspectNow(sourceBytes)) {
                is PhotoEditorInspectResult.Failure -> null
                is PhotoEditorInspectResult.Success ->
                    MediaPipeline
                        .decodeSampledBitmap(
                            bytes = sourceBytes,
                            maxEdgePx = PREVIEW_MAX_EDGE_PX,
                            honorExifOrientation = true,
                        )?.takeIf { it.width.toLong() * it.height <= PREVIEW_MAX_PIXELS }
            }
        }

    suspend fun render(
        sourceBytes: ByteArray,
        recipe: PhotoEditRecipe,
        quality: MediaQuality,
    ): PhotoEditorRenderResult =
        withContext(renderDispatcher) {
            processRenderMutex.withLock {
                currentCoroutineContext().ensureActive()
                val inspected = inspectNow(sourceBytes)
                if (inspected is PhotoEditorInspectResult.Failure) {
                    return@withLock PhotoEditorRenderResult.InvalidSource(inspected.reason)
                }
                val source = (inspected as PhotoEditorInspectResult.Success).source
                val plan = outputPlan(source, recipe, quality) ?: return@withLock PhotoEditorRenderResult.InvalidRecipe
                val decodeLongEdge = decodeLongEdge(source, plan.geometry)
                val decodedDimensions =
                    MediaPipeline.targetDimensions(
                        srcWidth = source.orientedSize.width,
                        srcHeight = source.orientedSize.height,
                        maxEdgePx = decodeLongEdge,
                    )
                if (!fitsMemoryBudget(sourceBytes.size, decodedDimensions, plan)) {
                    return@withLock PhotoEditorRenderResult.MemoryLimit
                }
                currentCoroutineContext().ensureActive()
                val decoded =
                    MediaPipeline.decodeSampledBitmap(
                        bytes = sourceBytes,
                        maxEdgePx = decodeLongEdge,
                        honorExifOrientation = true,
                    ) ?: return@withLock PhotoEditorRenderResult.DecodeFailed
                currentCoroutineContext().ensureActive()
                val rendered =
                    renderPixels(decoded, plan.geometry)
                        ?: return@withLock PhotoEditorRenderResult.RenderFailed
                currentCoroutineContext().ensureActive()
                val finalized =
                    MediaPipeline.finalizeRenderedImage(
                        rendered = rendered,
                        plan = plan.pipelinePlan,
                        onEncode = onEncode,
                    ) ?: return@withLock PhotoEditorRenderResult.EncodeFailed
                PhotoEditorRenderResult.Success(finalized, plan)
            }
        }

    internal fun outputPlan(
        source: PhotoEditorSourceInfo,
        recipe: PhotoEditRecipe,
        quality: MediaQuality,
    ): PhotoEditorOutputPlan? {
        if (!recipeMeetsMinimumCrop(source.orientedSize, recipe)) return null
        val maxEdge = min(quality.imageMaxEdgePx, MediaPipeline.EDITED_MAX_EDGE_PX)
        val geometry =
            runCatching {
                PhotoEditGeometry.create(
                    encodedSize = source.encodedSize,
                    exifOrientation = source.exifOrientation,
                    recipe = recipe,
                    maxEdgePx = maxEdge,
                    maxPixels = MediaPipeline.EDITED_MAX_PIXELS,
                )
            }.getOrNull() ?: return null
        val format =
            if (quality == MediaQuality.Original && source.mayHaveAlpha) {
                MediaPipeline.RenderedImageFormat.Png
            } else {
                MediaPipeline.RenderedImageFormat.Jpeg
            }
        val profileName =
            when (quality) {
                MediaQuality.Low -> "Low"
                MediaQuality.Standard -> "Standard"
                MediaQuality.High -> "High (HD)"
                MediaQuality.Original -> "Original (edited)"
            }
        return PhotoEditorOutputPlan(
            quality = quality,
            geometry = geometry,
            pipelinePlan =
                MediaPipeline.OutputPlan(
                    maxEdgePx = maxEdge,
                    maxPixels = MediaPipeline.EDITED_MAX_PIXELS,
                    format = format,
                    jpegQuality = quality.imageJpegQuality,
                ),
            effectiveLabel = "$profileName · ${geometry.outputSize.width} × ${geometry.outputSize.height}",
        )
    }

    @Suppress("CyclomaticComplexMethod") // Fail-closed source validation is intentionally centralized.
    private fun inspectNow(sourceBytes: ByteArray): PhotoEditorInspectResult {
        if (sourceBytes.isEmpty()) return PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.Empty)
        val mediaType =
            MediaPipeline.sniffImageMediaType(sourceBytes)
                ?: return PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.Unsupported)
        if (MediaPipeline.imageAnimationStatus(sourceBytes) != ImageAnimationStatus.STATIC) {
            return PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.Animated)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            when {
                width <= 0 || height <= 0 ->
                    PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.InvalidBounds)
                width > MAX_SOURCE_EDGE_PX || height > MAX_SOURCE_EDGE_PX ->
                    PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.DimensionLimit)
                width.toLong() * height.toLong() > MAX_SOURCE_PIXELS ->
                    PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.PixelLimit)
                max(width, height).toDouble() / min(width, height).toDouble() > MAX_SOURCE_ASPECT_RATIO ->
                    PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.AspectRatioLimit)
                else -> {
                    val orientation = MediaPipeline.readExifOrientation(sourceBytes)
                    val oriented = MediaPipeline.orientedSourceDimensions(width, height, orientation)
                    PhotoEditorInspectResult.Success(
                        PhotoEditorSourceInfo(
                            encodedSize = EditorPixelSize(width, height),
                            orientedSize = EditorPixelSize(oriented.first, oriented.second),
                            exifOrientation = orientation,
                            mediaType = mediaType,
                            mayHaveAlpha = mediaType == "image/png" || mediaType == "image/webp",
                        ),
                    )
                }
            }
        } catch (_: RuntimeException) {
            PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.InvalidBounds)
        } catch (_: OutOfMemoryError) {
            PhotoEditorInspectResult.Failure(PhotoEditorSourceFailure.InvalidBounds)
        }
    }

    private suspend fun renderPixels(
        decoded: Bitmap,
        geometry: PhotoEditGeometry,
    ): Bitmap? {
        var output: Bitmap? = null
        return try {
            output =
                Bitmap
                    .createBitmap(
                        geometry.outputSize.width,
                        geometry.outputSize.height,
                        Bitmap.Config.ARGB_8888,
                    ).apply { setHasAlpha(true) }
            val canvas = Canvas(output)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            val matrix = photoMatrix(decoded.width, decoded.height, geometry)
            canvas.save()
            canvas.concat(matrix)
            canvas.drawBitmap(
                decoded,
                0f,
                0f,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            canvas.restore()
            decoded.recycle()
            currentCoroutineContext().ensureActive()
            renderMarks(canvas, geometry)
            output
        } catch (_: OutOfMemoryError) {
            output?.recycle()
            null
        } catch (_: RuntimeException) {
            output?.recycle()
            null
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun photoMatrix(
        decodedWidth: Int,
        decodedHeight: Int,
        geometry: PhotoEditGeometry,
    ): Matrix {
        val affine = geometry.orientedBitmapToOutputAffine(decodedWidth, decodedHeight)
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    affine.scaleX,
                    affine.skewX,
                    affine.translateX,
                    affine.skewY,
                    affine.scaleY,
                    affine.translateY,
                    0f,
                    0f,
                    1f,
                ),
            )
        }
    }

    @Suppress("LongMethod", "NestedBlockDepth") // Tile nesting bounds peak memory for large edited images.
    private suspend fun renderMarks(
        outputCanvas: Canvas,
        geometry: PhotoEditGeometry,
    ) {
        if (geometry.recipe.strokes.isEmpty()) return
        val renderedStrokes = geometry.recipe.strokes.map { renderStroke(it, geometry) }
        val outputWidth = geometry.outputSize.width
        val outputHeight = geometry.outputSize.height
        var top = 0
        while (top < outputHeight) {
            currentCoroutineContext().ensureActive()
            var left = 0
            val bottom = min(top + MARK_TILE_CORE_PX, outputHeight)
            while (left < outputWidth) {
                val right = min(left + MARK_TILE_CORE_PX, outputWidth)
                val expandedLeft = max(0, left - MARK_TILE_OVERLAP_PX)
                val expandedTop = max(0, top - MARK_TILE_OVERLAP_PX)
                val expandedRight = min(outputWidth, right + MARK_TILE_OVERLAP_PX)
                val expandedBottom = min(outputHeight, bottom + MARK_TILE_OVERLAP_PX)
                val expandedBounds =
                    RectF(
                        expandedLeft.toFloat(),
                        expandedTop.toFloat(),
                        expandedRight.toFloat(),
                        expandedBottom.toFloat(),
                    )
                val intersecting = renderedStrokes.filter { RectF.intersects(it.bounds, expandedBounds) }
                if (intersecting.isNotEmpty()) {
                    val tile =
                        Bitmap
                            .createBitmap(
                                expandedRight - expandedLeft,
                                expandedBottom - expandedTop,
                                Bitmap.Config.ARGB_8888,
                            ).apply { setHasAlpha(true) }
                    try {
                        val tileCanvas = Canvas(tile)
                        tileCanvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        tileCanvas.translate(-expandedLeft.toFloat(), -expandedTop.toFloat())
                        intersecting.forEach { stroke ->
                            if (stroke.singlePoint != null) {
                                tileCanvas.drawCircle(
                                    stroke.singlePoint.x,
                                    stroke.singlePoint.y,
                                    stroke.paint.strokeWidth / 2f,
                                    stroke.paint,
                                )
                            } else {
                                tileCanvas.drawLines(stroke.linePoints, stroke.paint)
                            }
                        }
                        val sourceRect =
                            Rect(
                                left - expandedLeft,
                                top - expandedTop,
                                right - expandedLeft,
                                bottom - expandedTop,
                            )
                        outputCanvas.drawBitmap(
                            tile,
                            sourceRect,
                            Rect(left, top, right, bottom),
                            null,
                        )
                    } finally {
                        tile.recycle()
                    }
                }
                left = right
            }
            top = bottom
        }
    }

    private fun renderStroke(
        stroke: PhotoEditStroke,
        geometry: PhotoEditGeometry,
    ): RenderedStroke {
        val mappedPoints = stroke.points.map(geometry::orientedToOutput)
        val singlePoint = mappedPoints.singleOrNull()
        val linePoints = FloatArray(max(0, mappedPoints.size - 1) * 4)
        for (index in 0 until mappedPoints.lastIndex) {
            val first = mappedPoints[index]
            val second = mappedPoints[index + 1]
            val offset = index * 4
            linePoints[offset] = first.x
            linePoints[offset + 1] = first.y
            linePoints[offset + 2] = second.x
            linePoints[offset + 3] = second.y
        }
        val cropWidth = geometry.orientedSize.width * geometry.recipe.crop.width
        val cropHeight = geometry.orientedSize.height * geometry.recipe.crop.height
        val naturalWidth = if (geometry.recipe.quarterTurnsClockwise % 2 == 0) cropWidth else cropHeight
        val outputScale = geometry.outputSize.width / naturalWidth.coerceAtLeast(1f)
        val width =
            (stroke.widthFraction * min(geometry.orientedSize.width, geometry.orientedSize.height) * outputScale)
                .coerceAtLeast(1f)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = stroke.colorArgb
                if (stroke.mode == PhotoStrokeMode.Erase) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
        val bounds =
            RectF(
                mappedPoints.minOf { it.x },
                mappedPoints.minOf { it.y },
                mappedPoints.maxOf { it.x },
                mappedPoints.maxOf { it.y },
            )
        bounds.inset(-width / 2f - 1f, -width / 2f - 1f)
        return RenderedStroke(linePoints, paint, bounds, singlePoint)
    }

    private fun fitsMemoryBudget(
        sourceByteCount: Int,
        decodedDimensions: Pair<Int, Int>,
        plan: PhotoEditorOutputPlan,
    ): Boolean {
        val decodedPixels = decodedDimensions.first.toLong() * decodedDimensions.second.toLong()
        val outputPixels = plan.geometry.outputSize.pixels
        val sourceBytes = sourceByteCount.toLong()
        val tileBytes =
            (MARK_TILE_CORE_PX + MARK_TILE_OVERLAP_PX * 2).toLong() *
                (MARK_TILE_CORE_PX + MARK_TILE_OVERLAP_PX * 2) *
                BYTES_PER_PIXEL
        val renderPeak = sourceBytes + (decodedPixels + outputPixels) * BYTES_PER_PIXEL + tileBytes
        val estimatedEncoded =
            min(
                plan.pipelinePlan.maxEncodedBytes.toLong(),
                outputPixels * BYTES_PER_PIXEL + ENCODE_OVERHEAD_BYTES,
            )
        val outputCopies =
            if (plan.pipelinePlan.format == MediaPipeline.RenderedImageFormat.Jpeg) 2L else 1L
        val encodePeak =
            sourceBytes + outputPixels * BYTES_PER_PIXEL * outputCopies + estimatedEncoded * 2
        return max(renderPeak, encodePeak) <= memoryBudgetBytes()
    }

    private fun decodeLongEdge(
        source: PhotoEditorSourceInfo,
        geometry: PhotoEditGeometry,
    ): Int {
        val cropWidth = source.orientedSize.width * geometry.recipe.crop.width
        val cropHeight = source.orientedSize.height * geometry.recipe.crop.height
        val naturalWidth = if (geometry.recipe.quarterTurnsClockwise % 2 == 0) cropWidth else cropHeight
        val naturalHeight = if (geometry.recipe.quarterTurnsClockwise % 2 == 0) cropHeight else cropWidth
        val scale =
            min(
                geometry.outputSize.width / naturalWidth.coerceAtLeast(1f),
                geometry.outputSize.height / naturalHeight.coerceAtLeast(1f),
            ).coerceAtMost(1f)
        return ceil(max(source.orientedSize.width, source.orientedSize.height) * scale)
            .toInt()
            .coerceAtLeast(max(geometry.outputSize.width, geometry.outputSize.height))
            .coerceAtMost(max(source.orientedSize.width, source.orientedSize.height))
    }

    private data class RenderedStroke(
        val linePoints: FloatArray,
        val paint: Paint,
        val bounds: RectF,
        val singlePoint: EditorPoint?,
    )

    companion object {
        const val PREVIEW_MAX_EDGE_PX = 1536
        const val PREVIEW_MAX_PIXELS = 4_000_000L
        const val MAX_SOURCE_EDGE_PX = 32_768
        const val MAX_SOURCE_PIXELS = 200_000_000L
        const val MAX_SOURCE_ASPECT_RATIO = 100.0
        private const val BYTES_PER_PIXEL = 4L
        private const val MARK_TILE_CORE_PX = 512
        private const val MARK_TILE_OVERLAP_PX = 2
        private const val ENCODE_OVERHEAD_BYTES = 1024L * 1024L
        private val processRenderMutex = Mutex()
    }
}

private fun recipeMeetsMinimumCrop(
    orientedSize: EditorPixelSize,
    recipe: PhotoEditRecipe,
): Boolean {
    val minimumPixels = max(32f, min(orientedSize.width, orientedSize.height) * 0.01f)
    return recipe.crop.width * orientedSize.width >= minimumPixels &&
        recipe.crop.height * orientedSize.height >= minimumPixels
}

private fun defaultEditorMemoryBudgetBytes(): Long =
    min(
        128L * 1024L * 1024L,
        Runtime.getRuntime().maxMemory() / 3L,
    )
