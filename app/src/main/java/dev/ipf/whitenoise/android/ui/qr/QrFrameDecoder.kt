package dev.ipf.whitenoise.android.ui.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** One decoder per CameraX analysis executor; ordinary frames without a QR are not errors. */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): String? {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(luminance.size == width * height)
        require(rotationDegrees in setOf(0, QUARTER_TURN, HALF_TURN, THREE_QUARTER_TURN))
        val frame = rotateLuminance(luminance, width, height, rotationDegrees)
        val outputWidth = if (rotationDegrees % HALF_TURN == 0) width else height
        val outputHeight = if (rotationDegrees % HALF_TURN == 0) height else width
        val source =
            PlanarYUVLuminanceSource(
                frame,
                outputWidth,
                outputHeight,
                0,
                0,
                outputWidth,
                outputHeight,
                false,
            )
        for (candidate in listOf(source, source.invert())) {
            try {
                return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (_: ReaderException) {
                // Most analysis frames legitimately contain no decodable QR code.
            } finally {
                reader.reset()
            }
        }
        return null
    }

    internal companion object {
        const val MAX_PIXELS = 4_194_304L
    }
}

internal data class QrLuminanceCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/** Copy only the crop's Y samples, respecting buffer position and CameraX row/pixel padding. */
internal fun copyQrLuminance(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    crop: QrLuminanceCrop,
): ByteArray {
    val cropLeft = crop.left
    val cropTop = crop.top
    val cropWidth = crop.width
    val cropHeight = crop.height
    require(rowStride > 0 && pixelStride > 0 && cropLeft >= 0 && cropTop >= 0)
    require(cropWidth > 0 && cropHeight > 0 && cropWidth.toLong() * cropHeight <= QrFrameDecoder.MAX_PIXELS)
    require((cropLeft.toLong() + cropWidth - 1) * pixelStride < rowStride)
    val view = buffer.asReadOnlyBuffer()
    val start = view.position().toLong()
    val last =
        start + (cropTop.toLong() + cropHeight - 1) * rowStride +
            (cropLeft.toLong() + cropWidth - 1) * pixelStride
    require(last < view.limit())
    return ByteArray(cropWidth * cropHeight) { index ->
        val offset =
            start + (cropTop.toLong() + index / cropWidth) * rowStride +
                (cropLeft.toLong() + index % cropWidth) * pixelStride
        view.get(offset.toInt())
    }
}

/** Rotate the bounded crop into the camera's reported display orientation. */
private fun rotateLuminance(
    input: ByteArray,
    width: Int,
    height: Int,
    rotation: Int,
): ByteArray {
    if (rotation == 0) return input
    val outputWidth = if (rotation % HALF_TURN == 0) width else height
    return ByteArray(input.size).also { output ->
        for (y in 0 until height) {
            for (x in 0 until width) {
                val destination =
                    when (rotation) {
                        QUARTER_TURN -> x * outputWidth + height - 1 - y
                        HALF_TURN -> (height - 1 - y) * outputWidth + width - 1 - x
                        else -> (width - 1 - x) * outputWidth + y
                    }
                output[destination] = input[y * width + x]
            }
        }
    }
}

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270
