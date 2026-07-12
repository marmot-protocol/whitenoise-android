package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.Radii
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Hoisted open/close state for the composer attachment sheet. Owned by the
 * conversation screen so an outside tap on the transcript can dismiss the
 * sheet, while the open transition (which coordinates focus, IME, and the
 * emoji pane) stays inside ComposerBar.
 */
@Stable
internal class ComposerAttachmentSheetState {
    var isOpen by mutableStateOf(false)
        private set

    fun open() {
        isOpen = true
    }

    fun dismiss() {
        isOpen = false
    }
}

@Composable
internal fun rememberComposerAttachmentSheetState(): ComposerAttachmentSheetState = remember { ComposerAttachmentSheetState() }

internal val ComposerAttachmentSheetDragDismissThreshold = 96.dp

@Composable
internal fun ComposerAttachmentSheetPane(
    height: Dp,
    alpha: Float,
    onPickFromGallery: (() -> Unit)?,
    onPickDocument: (() -> Unit)?,
    onShareLocation: (() -> Unit)?,
    onShareContact: (() -> Unit)?,
    onComingSoon: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissThresholdPx = with(LocalDensity.current) { ComposerAttachmentSheetDragDismissThreshold.toPx() }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .alpha(alpha)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = { if (dragTotal > dismissThresholdPx) onDismiss() },
                    ) { _, dragAmount -> dragTotal += dragAmount }
                },
        shape = RoundedCornerShape(topStart = Radii.xl, topEnd = Radii.xl),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = Dimens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .padding(top = Dimens.spaceSm)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentActionTile(
                        icon = Icons.Default.PhotoLibrary,
                        label = stringResource(R.string.attach_gallery),
                        available = onPickFromGallery != null,
                        onClick = onPickFromGallery ?: onComingSoon,
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentActionTile(
                        icon = Icons.Default.Description,
                        label = stringResource(R.string.attach_document),
                        available = onPickDocument != null,
                        onClick = onPickDocument ?: onComingSoon,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AttachmentActionTile(
                        icon = Icons.Default.LocationOn,
                        label = stringResource(R.string.attach_location),
                        available = onShareLocation != null,
                        onClick = onShareLocation ?: onComingSoon,
                        modifier = Modifier.weight(1f),
                    )
                    AttachmentActionTile(
                        icon = Icons.Default.Person,
                        label = stringResource(R.string.attach_contact),
                        available = onShareContact != null,
                        onClick = onShareContact ?: onComingSoon,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentActionTile(
    icon: ImageVector,
    label: String,
    available: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(Radii.lg))
                .clickable(onClick = onClick)
                .padding(vertical = Dimens.spaceSm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
    ) {
        Surface(
            shape = CircleShape,
            color =
                if (available) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            border = amoledSurfaceBorderStroke(),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    if (available) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier =
                    Modifier
                        .padding(Dimens.spaceLg)
                        .size(28.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        if (!available) {
            Text(
                stringResource(R.string.coming_soon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
