package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.core.QrCodeEncoder

/** Black-on-white QR code on a large-corner white card sized from the available width. */
@Suppress("FunctionNaming")
@Composable
internal fun IdentityQrCodeSurface(
    value: String,
    availableWidth: Dp,
    contentDescription: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val matrixSize =
        (availableWidth * IdentityCodeDefaults.QR_WIDTH_FRACTION)
            .coerceIn(IdentityCodeDefaults.QrMinSize, IdentityCodeDefaults.QrMaxSize) - 16.dp
    Surface(
        modifier = modifier.size(matrixSize + IdentityCodeDefaults.QrSurfaceInset * 2).testTag(testTag),
        shape = MaterialTheme.shapes.large,
        color = Color.White,
    ) {
        IdentityQrCode(
            content = value,
            contentDescription = contentDescription,
            modifier = Modifier.padding(IdentityCodeDefaults.QrSurfaceInset),
        )
    }
}

/**
 * Encodes [content] once per value and draws it edge to edge inside the given bounds. Encoding is synchronous so
 * the first frame already carries the code; a profile link is short enough for that to stay well under a frame.
 */
@Suppress("FunctionNaming")
@Composable
private fun IdentityQrCode(
    content: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(content) { QrCodeEncoder.moduleMatrix(content, marginModules = 0) }
    // Drawn module by module across the whole box. A pre-rendered bitmap lays out at its own
    // pixel count, which left the code marooned in the middle of the white card.
    Canvas(
        modifier = modifier.aspectRatio(1f).semantics { this.contentDescription = contentDescription },
    ) {
        drawRect(Color.White)
        val cell = size.minDimension / matrix.width
        for (row in 0 until matrix.height) {
            for (column in 0 until matrix.width) {
                if (!matrix[column, row]) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(column * cell, row * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

/** Geometry the identity surfaces share: the QR surface's size band. */
internal object IdentityCodeDefaults {
    val QrSurfaceInset = 12.dp
    val QrMinSize = 248.dp
    val QrMaxSize = 376.dp
    const val QR_WIDTH_FRACTION = 0.81f
    const val QR_PIXELS = 512
}
