package dev.ipf.whitenoise.android.ui.conversation.composer

import android.view.RoundedCorner
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.RecentMediaStrip
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

internal data class ComposerAttachmentPaneBottomCorners(
    val start: Dp,
    val end: Dp,
)

internal fun composerAttachmentPaneBottomCorners(
    bottomLeftRadiusPx: Int,
    bottomRightRadiusPx: Int,
    density: Float,
    layoutDirection: LayoutDirection,
): ComposerAttachmentPaneBottomCorners {
    val safeDensity = density.takeIf { it > 0f } ?: 1f
    val left = (bottomLeftRadiusPx.coerceAtLeast(0) / safeDensity).dp
    val right = (bottomRightRadiusPx.coerceAtLeast(0) / safeDensity).dp
    return when (layoutDirection) {
        LayoutDirection.Ltr -> ComposerAttachmentPaneBottomCorners(start = left, end = right)
        LayoutDirection.Rtl -> ComposerAttachmentPaneBottomCorners(start = right, end = left)
    }
}

@Composable
private fun rememberComposerAttachmentPaneBottomCorners(): ComposerAttachmentPaneBottomCorners {
    val view = LocalView.current
    val density = LocalDensity.current.density
    val layoutDirection = LocalLayoutDirection.current
    var physicalRadii by remember(view) {
        mutableStateOf(readBottomRoundedCornerRadii(view))
    }

    DisposableEffect(view) {
        val listener =
            View.OnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
                physicalRadii = readBottomRoundedCornerRadii(changedView)
            }
        view.addOnLayoutChangeListener(listener)
        physicalRadii = readBottomRoundedCornerRadii(view)
        view.requestApplyInsets()
        onDispose { view.removeOnLayoutChangeListener(listener) }
    }

    return composerAttachmentPaneBottomCorners(
        bottomLeftRadiusPx = physicalRadii.first,
        bottomRightRadiusPx = physicalRadii.second,
        density = density,
        layoutDirection = layoutDirection,
    )
}

private fun readBottomRoundedCornerRadii(view: View): Pair<Int, Int> {
    val insets = view.rootWindowInsets
    val bottomLeft = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    val bottomRight = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
    return bottomLeft to bottomRight
}

@Composable
internal fun ComposerAttachmentSheetPane(
    alpha: Float,
    minimumHeight: androidx.compose.ui.unit.Dp,
    onPickRecentMedia: ((android.net.Uri) -> Unit)?,
    onPickFromGallery: (() -> Unit)?,
    onCaptureFromCamera: (() -> Unit)?,
    onPickDocument: (() -> Unit)?,
    onShareLocation: (() -> Unit)?,
    onShareUser: (() -> Unit)?,
    onShareContact: (() -> Unit)?,
    onComingSoon: () -> Unit,
    bottomCornersOverride: ComposerAttachmentPaneBottomCorners? = null,
    modifier: Modifier = Modifier,
) {
    val platformBottomCorners = rememberComposerAttachmentPaneBottomCorners()
    val bottomCorners = bottomCornersOverride ?: platformBottomCorners

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = minimumHeight)
                .clipToBounds()
                .alpha(alpha),
        shape =
            RoundedCornerShape(
                topStart = 0.dp,
                topEnd = 0.dp,
                bottomStart = bottomCorners.start,
                bottomEnd = bottomCorners.end,
            ),
        color = MaterialTheme.colorScheme.surface,
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        // The top edge stays flush with the composer while the bottom edge
        // follows the physical display corners. Height wraps the two tile rows
        // rather than matching the keyboard, so the sheet is only as tall as it
        // needs to be (no dead space below). During an IME-to-pane handoff,
        // [minimumHeight] follows the shrinking keyboard inset so the composer
        // moves only once, in lockstep with the system animation, before
        // settling at this natural content height.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
        ) {
            // Recent-media strip fills the space the pane reserves; it owns its
            // own opt-in permission and stays absent until the gallery action is
            // wired (same availability as the Gallery tile).
            if (onPickRecentMedia != null) {
                RecentMediaStrip(onPick = onPickRecentMedia)
            }
            // Two rows of three. Row 1 is capture/files (Gallery, Camera,
            // Document); row 2 is place/people (Location, User, Contact).
            // User (npub, actionable) and Contact (phone, informational) sit
            // adjacent but read as distinct — different icons and labels —
            // so a phone number never looks like an in-app identity.
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
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(R.string.attach_take_photo),
                    available = onCaptureFromCamera != null,
                    onClick = onCaptureFromCamera ?: onComingSoon,
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
                    label = stringResource(R.string.attach_user),
                    available = onShareUser != null,
                    onClick = onShareUser ?: onComingSoon,
                    modifier = Modifier.weight(1f),
                )
                AttachmentActionTile(
                    icon = Icons.Default.Contacts,
                    label = stringResource(R.string.attach_contact),
                    available = onShareContact != null,
                    onClick = onShareContact ?: onComingSoon,
                    modifier = Modifier.weight(1f),
                )
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
    // Neutral circular chip using the theme's default content color (no accent
    // fill). Compact sizing (24dp icon in a ~48dp circle) with tight spacing.
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(Radii.lg))
                .clickable(onClick = onClick)
                .padding(vertical = Dimens.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = amoledSurfaceBorderStroke(),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    if (available) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                modifier =
                    Modifier
                        .padding(Dimens.spaceMd)
                        .size(24.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
        )
        if (!available) {
            Text(
                stringResource(R.string.coming_soon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}
