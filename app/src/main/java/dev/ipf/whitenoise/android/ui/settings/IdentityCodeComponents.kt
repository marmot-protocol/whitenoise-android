package dev.ipf.whitenoise.android.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
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
    val image = remember(content) { qrImage(content) }
    Box(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit)
    }
}

/** Renders the QR matrix for [content] into a square bitmap at the encoder's fixed pixel size. */
private fun qrImage(content: String): ImageBitmap {
    val size = IdentityCodeDefaults.QR_PIXELS
    val pixels =
        QrCodeEncoder.pixels(
            content = content,
            size = size,
            onColor = android.graphics.Color.BLACK,
            offColor = android.graphics.Color.WHITE,
        )
    return Bitmap
        .createBitmap(size, size, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(pixels, 0, size, 0, 0, size, size) }
        .asImageBitmap()
}

/**
 * 48 dp copy target around a 240 dp capsule that shows a middle-ellipsized identifier and a copy glyph, which turns
 * into a check while [copied]. The whole target is the button; the capsule is the visual.
 */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun IdentifierCopyCapsule(
    value: String,
    copied: Boolean,
    onCopy: () -> Unit,
    copyContentDescription: String,
    copiedContentDescription: String,
    notCopiedStateDescription: String,
    copiedStateDescription: String,
    targetTestTag: String,
    visualTestTag: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val actionDescription = if (copied) copiedContentDescription else copyContentDescription
    val state = if (copied) copiedStateDescription else notCopiedStateDescription
    Box(
        modifier =
            modifier
                .widthIn(max = IdentityCodeDefaults.CapsuleMaxWidth)
                .heightIn(min = IdentityCodeDefaults.CapsuleMinHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onCopy,
                ).testTag(targetTestTag)
                .semantics {
                    role = Role.Button
                    contentDescription = actionDescription
                    stateDescription = state
                },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier
                    .width(IdentityCodeDefaults.CapsuleWidth)
                    .testTag(visualTestTag)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .indication(interactionSource, ripple())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            Icon(
                painter = painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy),
                contentDescription = null,
                modifier = Modifier.size(IdentityCodeDefaults.CapsuleIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Geometry the identity surfaces share: capsule width and the QR surface's size band. */
internal object IdentityCodeDefaults {
    val CapsuleWidth = 240.dp
    val CapsuleMaxWidth = 360.dp
    val CapsuleMinHeight = 48.dp
    val CapsuleIconSize = 16.dp
    val QrSurfaceInset = 12.dp
    val QrMinSize = 248.dp
    val QrMaxSize = 376.dp
    const val QR_WIDTH_FRACTION = 0.81f
    const val QR_PIXELS = 512
}
