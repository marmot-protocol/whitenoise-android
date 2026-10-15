package dev.ipf.whitenoise.android.media.editor

import android.graphics.Bitmap
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Pixel acquisition planning; editor rendering owns its output/marks/encoding lifetime. */
internal object PhotoEditorDecoder {
    data class DecodeSpec(
        val region: dev.ipf.whitenoise.android.media.sandbox.MediaImageRegion?,
        val edge: Int,
        val capacity: Int,
        val dimensions: Pair<Int, Int>,
    )

    fun plan(
        source: PhotoEditorSourceInfo,
        geometry: PhotoEditGeometry,
    ): DecodeSpec? {
        val fullEdge = decodeLongEdge(source, geometry)
        val region =
            if (fullEdge > MediaDecodePolicy.MAX_EDGE) {
                geometry.encodedCropRegion()
            } else {
                null
            }
        val edge = if (region == null) fullEdge else max(geometry.outputSize.width, geometry.outputSize.height)
        val capacity =
            MediaDecodePolicy
                .outputCapacity(edge)
                ?.takeIf { region == null || MediaDecodePolicy.regionSampleSize(region, edge) != null }
                ?: return null
        return DecodeSpec(
            region,
            edge,
            capacity,
            MediaPipeline.targetDimensions(
                region?.width ?: source.orientedSize.width,
                region?.height ?: source.orientedSize.height,
                edge,
            ),
        )
    }

    fun decode(
        sourceBytes: ByteArray,
        spec: DecodeSpec,
    ): Bitmap? =
        if (spec.region != null) {
            dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
                .region(sourceBytes, spec.region, spec.edge)
        } else {
            MediaPipeline.decodeSampledBitmap(sourceBytes, spec.edge, honorExifOrientation = true)
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
}
