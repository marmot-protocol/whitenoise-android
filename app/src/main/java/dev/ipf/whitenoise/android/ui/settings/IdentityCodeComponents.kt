package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.QrCodeEncoder
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.animatePublicKeyExpansion
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress

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

/**
 * The collapsed 240 dp capsule shows an abbreviated identifier beside a copy glyph. On copy, the capsule uses
 * the available width to display the grouped key, and the glyph turns into a check while [copied].
 * [explanationRes] defaults to the wording for the user's own key.
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
    @StringRes explanationRes: Int = R.string.explanation_organize_copy_public_key,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var expanded by rememberSaveable(value) { mutableStateOf(false) }
    val actionDescription = if (copied) copiedContentDescription else copyContentDescription
    val state = if (copied) copiedStateDescription else notCopiedStateDescription
    Box(
        modifier =
            modifier
                .heightIn(min = IdentityCodeDefaults.CapsuleMinHeight)
                .explainOnLongPress(
                    Explanation(
                        copyContentDescription,
                        stringResource(explanationRes),
                    ),
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        onCopy()
                        expanded = true
                    },
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
                    .then(if (expanded) Modifier.fillMaxWidth() else Modifier.width(IdentityCodeDefaults.CapsuleWidth))
                    .testTag(visualTestTag)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .indication(interactionSource, ripple())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) publicKeyDisplayGroups(value).joinToString(" ") else value,
                modifier = Modifier.weight(1f).animatePublicKeyExpansion(expanded),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                fontFamily = if (expanded) FontFamily.Monospace else null,
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
    val CapsuleMinHeight = 48.dp
    val CapsuleIconSize = 16.dp
    val QrSurfaceInset = 12.dp
    val QrMinSize = 248.dp
    val QrMaxSize = 376.dp
    const val QR_WIDTH_FRACTION = 0.81f
    const val QR_PIXELS = 512
}
