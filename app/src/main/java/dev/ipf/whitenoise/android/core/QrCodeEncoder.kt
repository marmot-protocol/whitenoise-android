package dev.ipf.whitenoise.android.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeEncoder {
    // Upper bound on a QR side length. A QR shown in a dialog is ~1k px even on
    // the densest screens; 2048 is generous. The cap prevents `size * size`
    // from overflowing Int (which would yield a negative IntArray length and
    // throw NegativeArraySizeException) and bounds the allocation. See #169.
    const val MAX_QR_DIMENSION = 2048

    fun matrix(
        content: String,
        size: Int,
    ): BitMatrix {
        require(content.isNotBlank()) { "QR content cannot be blank" }
        require(size > 0) { "QR size must be positive" }
        require(size <= MAX_QR_DIMENSION) { "QR size $size exceeds max $MAX_QR_DIMENSION" }
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }

    fun pixels(
        content: String,
        size: Int,
        onColor: Int,
        offColor: Int,
    ): IntArray {
        val matrix = matrix(content, size)
        require(matrix.width == size && matrix.height == size) {
            "Requested ${size}x$size QR, but encoder returned ${matrix.width}x${matrix.height}. Request a larger size."
        }
        return IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (matrix[x, y]) onColor else offColor
        }
    }
}
