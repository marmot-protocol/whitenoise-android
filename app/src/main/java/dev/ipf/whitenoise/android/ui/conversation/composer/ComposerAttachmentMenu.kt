@file:Suppress("FunctionNaming", "MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** Above-Add placement mirrors the pinned prototype, with its below-anchor fallback in short windows. */
internal class ComposerAttachmentMenuPositionProvider(
    private val sourceBounds: IntRect,
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    /** Anchors the menu above the attachment button, clamped to the window. */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX =
            if (layoutDirection == LayoutDirection.Ltr) {
                sourceBounds.left
            } else {
                sourceBounds.right - popupContentSize.width
            }
        val availableWidth = windowSize.width - edgePx * 2
        val x =
            if (popupContentSize.width >= availableWidth) {
                (windowSize.width - popupContentSize.width) / 2
            } else {
                preferredX.coerceIn(edgePx, windowSize.width - edgePx - popupContentSize.width)
            }
        val aboveY = sourceBounds.top - gapPx - popupContentSize.height
        val belowY = sourceBounds.bottom + gapPx
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - edgePx
        val y = if (aboveY >= edgePx || !fitsBelow) aboveY.coerceAtLeast(edgePx) else belowY
        return IntOffset(x, y)
    }
}

/** Native acquisition callbacks in the prototype command surface; no picker or permission owner is replaced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun ComposerAttachmentMenu(
    anchorBounds: IntRect,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCamera: (() -> Unit)?,
    onGallery: (() -> Unit)?,
    onFiles: (() -> Unit)?,
    onLocation: (() -> Unit)?,
    onUser: (() -> Unit)?,
    onContact: (() -> Unit)?,
) {
    val items =
        listOfNotNull(
            onCamera?.let { WhiteNoiseMenuItem(stringResource(R.string.attachment_camera), it, R.drawable.ic_camera) },
            onGallery?.let {
                WhiteNoiseMenuItem(stringResource(R.string.attachment_photos_videos), it, R.drawable.ic_image)
            },
            onFiles?.let { WhiteNoiseMenuItem(stringResource(R.string.download_files), it, R.drawable.ic_description) },
            onLocation?.let {
                WhiteNoiseMenuItem(stringResource(R.string.attach_location), it, R.drawable.ic_location_on)
            },
            onUser?.let { WhiteNoiseMenuItem(stringResource(R.string.attach_contact), it, R.drawable.ic_person) },
            onContact?.let {
                WhiteNoiseMenuItem(stringResource(R.string.attachment_device_contact), it, R.drawable.ic_person)
            },
        )
    val density = LocalDensity.current
    val position =
        remember(anchorBounds, density) {
            ComposerAttachmentMenuPositionProvider(
                sourceBounds = anchorBounds,
                gapPx = with(density) { 10.dp.roundToPx() },
                edgePx = with(density) { 8.dp.roundToPx() },
            )
        }
    KeyboardSafePopup(expanded, onDismiss, position) {
        BoxWithConstraints {
            val menuMaxHeight = (maxHeight - 16.dp).coerceAtLeast(0.dp)
            // A popup hands its content the window's width, and the Expressive menu rows fill
            // whatever they are given, so without this the menu ran edge to edge. The prototype's
            // menu popup sizes to its widest label; wrapping to the intrinsic width matches it.
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShapes(),
                border = amoledOutlineBorder(),
                shadowElevation = MenuDefaults.ShadowElevation,
                modifier = Modifier.width(IntrinsicSize.Max).testTag("conversation.attachment.menu"),
            ) {
                Column(
                    Modifier
                        .heightIn(max = menuMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            leadingIcon = {
                                item.icon?.let { Icon(painterResource(it), null, Modifier.size(24.dp)) }
                            },
                            shape = MenuDefaults.itemShape(index, items.size).shape,
                            colors = MenuDefaults.selectableItemColors(),
                            onClick = {
                                onDismiss()
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}
