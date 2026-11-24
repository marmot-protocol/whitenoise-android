package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.conversation.media.OutboundShareTooLargeException
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentSource
import dev.ipf.whitenoise.android.ui.conversation.media.presentMediaLaunchFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * Owns photo selection for one gallery route. The share is checked against [resolvePhotos] when available, otherwise
 * against the composed [tiles], and may open a share sheet only while this gallery's account, runtime and
 * conversation still own the conversation's foreground presentation.
 */
@Composable
internal fun rememberSharedPhotoShare(
    tiles: SharedMediaTiles,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    resolvePhotos: SelectedPhotoResolver?,
): SharedPhotoShare {
    val currentTiles by rememberUpdatedState(tiles)
    val owner = remember { Triple(appState.activeAccountRef, controller.boundAccountRef, appState.runtimeGeneration) }
    return rememberSharedPhotoShare(
        ownsShare = {
            controller.ownsRetainedForegroundPresentation &&
                Triple(appState.activeAccountRef, controller.boundAccountRef, appState.runtimeGeneration) == owner &&
                !appState.appLock.screenVisible &&
                !appState.signOutInProgress &&
                !appState.wipeInProgress
        },
        accountRef = { controller.conversationAccountRef },
        currentPhotos = resolvePhotos ?: { currentTiles.visuals },
        source = { photo ->
            attachmentSource(controller, photo.messageIdHex, photo.attachmentIndex, photo.reference, photo.mine)
        },
        onFailure = { selection, error -> presentSharedPhotosFailure(appState, selection, error) },
    )
}

/**
 * The share runs in this composition's scope, so closing the gallery or switching account, runtime or conversation
 * cancels staging and deletes what was staged. Back ends selection mode before it leaves the gallery. A failure is
 * reported only while [ownsShare] holds. [currentPhotos] returns the gallery's current tiles for the selected photos.
 */
@Suppress("LongParameterList")
@Composable
internal fun rememberSharedPhotoShare(
    ownsShare: () -> Boolean,
    accountRef: () -> String?,
    currentPhotos: SelectedPhotoResolver,
    source: suspend (SharedMediaTile) -> AttachmentPlaintext,
    onFailure: (SharedPhotoSelection, Throwable) -> Unit,
): SharedPhotoShare {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.shared_media_share)
    val state = remember { SharedPhotoShare() }
    BackHandler(enabled = state.selection.active, onBack = state::exit)
    state.start = start@{
        if (state.sharing || !state.selection.active || !ownsShare()) return@start
        val photos = state.selection.photos
        val account = accountRef() ?: return@start
        // Recorded before it starts, so the share is visible as staging before it can finish or fail.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext()[Job]
                try {
                    runCatchingCancellable {
                        // Ownership is checked again once the gallery is read, as reading it can suspend.
                        val current =
                            suspend { if (ownsShare()) currentPhotos(photos).takeIf { ownsShare() } else null }
                        shareSelectedPhotos(context, account, photos, chooserTitle, current, source)
                    }.onSuccess {
                        state.selection.clear()
                    }.onFailure { error ->
                        if (ownsShare()) onFailure(state.selection, error)
                    }
                } finally {
                    state.end(self)
                }
            }
        state.begin(job)
        job.start()
    }
    return state
}

/** Expected refusals explain what to change; anything else is reported as a share failure. */
private fun presentSharedPhotosFailure(
    appState: WhiteNoiseAppState,
    selection: SharedPhotoSelection,
    error: Throwable,
) {
    when (error) {
        is SharedPhotosUnavailableException -> {
            selection.deselect(error.photos)
            appState.present(R.string.shared_media_share_photos_failed, R.string.shared_media_share_photos_unavailable)
        }
        is OutboundShareTooLargeException ->
            appState.present(R.string.shared_media_share_photos_failed, R.string.shared_media_share_photos_too_large)
        else ->
            appState.presentMediaLaunchFailure(
                R.string.shared_media_share_photos_failed,
                "MEDIA_LIBRARY_PHOTOS_SHARE",
                error,
            )
    }
}

/** The bar that replaces the filter chips while selecting, or null outside selection mode. */
internal fun SharedPhotoShare.selectionBar(loading: Boolean): (@Composable () -> Unit)? =
    if (!selection.active) {
        null
    } else {
        {
            SharedPhotoSelectionBar(
                count = selection.count,
                sharing = sharing,
                shareEnabled = !loading,
                onClose = ::exit,
                onShare = { start() },
            )
        }
    }

/** Replaces the filter chips while selecting: cancel, the live selected count and one Share for the whole set. */
@Suppress("FunctionNaming")
@Composable
internal fun SharedPhotoSelectionBar(
    count: Int,
    sharing: Boolean,
    shareEnabled: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("shared.selection.bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("shared.selection.close")) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.shared_media_cancel_selection))
        }
        Text(
            pluralStringResource(R.plurals.shared_media_photos_selected_count, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (sharing) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp).testTag("shared.selection.sharing"), strokeWidth = 2.dp)
            }
        } else {
            IconButton(
                onClick = onShare,
                enabled = shareEnabled,
                modifier = Modifier.testTag("shared.selection.share"),
            ) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.shared_media_share_selected_photos))
            }
        }
    }
}

/** The custom action that starts selection from a photo, for people who cannot long-press. */
@Composable
internal fun Modifier.selectPhotoAction(
    enabled: Boolean,
    onSelect: () -> Unit,
): Modifier {
    if (!enabled) return this
    val label = stringResource(R.string.shared_media_select_photo)
    val select by rememberUpdatedState(onSelect)
    return semantics {
        customActions =
            listOf(
                CustomAccessibilityAction(label) {
                    select()
                    true
                },
            )
    }
}

/**
 * Covers one tile while selecting, taking over its touch and accessibility. A photo toggles as a checkbox with a check
 * mark; a video is dimmed and cannot be selected or opened until selection ends.
 */
@Suppress("FunctionNaming")
@Composable
internal fun BoxScope.SharedTileSelectionToggle(
    tile: SharedMediaTile,
    selected: Boolean,
    enabled: Boolean,
    description: String,
    onToggle: () -> Unit,
) {
    if (tile.isVideo) {
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = UNSELECTABLE_SCRIM_ALPHA))
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .clearAndSetSemantics {
                    contentDescription = description
                    disabled()
                },
        )
        return
    }
    Box(
        Modifier
            .matchParentSize()
            .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .testTag("shared.selection.tile.${tile.messageIdHex}#${tile.attachmentIndex}"),
    ) {
        SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

/** A selected photo shrinks inside an outline, so the selection reads at a glance; the change is animated. */
@Composable
internal fun Modifier.selectedTileInset(selected: Boolean): Modifier {
    val scale by animateFloatAsState(if (selected) SELECTED_TILE_SCALE else 1f, label = "sharedTileSelection")
    val outlined =
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    return if (selected) {
        outlined.border(SELECTED_OUTLINE_WIDTH, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
    } else {
        outlined
    }
}

/** A filled check when selected, otherwise an empty ring readable over any photo. */
@Suppress("FunctionNaming")
@Composable
private fun SelectionMark(
    selected: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier
            .size(24.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = UNSELECTED_MARK_ALPHA),
                CircleShape,
            ).border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** How much a selected photo shrinks inside its cell. */
private const val SELECTED_TILE_SCALE = 0.88f

private val SELECTED_OUTLINE_WIDTH = 3.dp

/** Keeps the empty selection ring readable over bright photos. */
private const val UNSELECTED_MARK_ALPHA = 0.3f

private const val UNSELECTABLE_SCRIM_ALPHA = 0.7f
