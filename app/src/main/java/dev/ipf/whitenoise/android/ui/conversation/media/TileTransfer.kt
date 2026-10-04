@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.attachmentCancellationState
import dev.ipf.whitenoise.android.state.attachmentFilePresentationState
import dev.ipf.whitenoise.android.state.attachmentNativeProgress
import dev.ipf.whitenoise.android.state.cancelAttachmentTransfer
import dev.ipf.whitenoise.android.state.hasCachedAttachmentInMemory
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import kotlinx.coroutines.flow.emptyFlow

/** A tile's transfer control stays at least this large so Cancel and Retry are reachable by touch and TalkBack. */
internal val TILE_TRANSFER_CONTROL_SIZE: Dp = 52.dp

private val TileTransferRingStroke = 2.5.dp

/** The ring sits inside its slot so a round clip never trims the stroke. */
private val TileTransferRingInset = 8.dp
private val TileTransferGlyphSize = 22.dp
private val TileTransferCaptionCorner = 10.dp
private val TileTransferCaptionPadding = 6.dp
private val TileTransferCaptionGap = 6.dp

/**
 * What one visual-media tile knows about its native transfer: the state the file card would show, the real body
 * progress, whether the reader's Cancel is still pending acknowledgement, whether an earlier Cancel suppresses the
 * automatic path, and whether the tile's own materialization already failed ([failedLocally]). [onCancel] revokes
 * platform delivery at once and publishes Cancelled only after native acknowledgement.
 */
@Immutable
internal class TileTransfer(
    val state: AttachmentTransferState,
    val progress: NativeAttachmentProgress?,
    val cancellation: AttachmentCancellationState,
    val suppressed: Boolean,
    val failedLocally: Boolean = false,
    val onCancel: () -> Unit,
) {
    /** The reader's Cancel was sent and the engine has not yet acknowledged it. */
    val cancelling: Boolean get() = cancellation == AttachmentCancellationState.Pending

    /** No Cancel is pending or unconfirmed, so the host and native state describe the transfer as it is. */
    private val unobstructed: Boolean get() = cancellation == AttachmentCancellationState.None

    /**
     * A transfer is queued, receiving, verifying or waiting to retry, and can still be cancelled. An unresolved host
     * state is not a transfer: until the engine or the host says otherwise the tile keeps its own idle presentation.
     */
    val active: Boolean get() = unobstructed && state == AttachmentTransferState.Downloading

    /** The reader cancelled and the engine confirmed it, so the tile offers Download again. */
    val cancelled: Boolean get() = unobstructed && state == AttachmentTransferState.Cancelled

    /** The transfer failed, the tile's own materialization failed, or the engine did not confirm a Cancel: Retry. */
    val failed: Boolean
        get() =
            failedLocally ||
                cancellation == AttachmentCancellationState.Unconfirmed ||
                (unobstructed && state == AttachmentTransferState.Failed)

    /** The tile replaces its spinner or Download action with the transfer control. */
    val visible: Boolean get() = cancelling || active || cancelled || failed

    /** Determinate only while the engine reports a trustworthy total for the body being received. */
    val fraction: Float? get() = progress?.fraction?.takeIf { unobstructed }
}

/**
 * Observes the shared native transfer of one attachment for a tile, without requesting, restarting or cancelling it.
 *
 * The engine feed is opened only while [observeNative] is true, that is while the tile is materializing, or while the
 * host reports another surface downloading the attachment ([tileObservesNative]), so idle tiles in a long conversation
 * hold no subscription. Own sends have no download to show and ignore native phases.
 */
@Composable
internal fun rememberTileTransfer(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
    suppressed: Boolean,
    observeNative: Boolean,
    failedLocally: Boolean = false,
): TileTransfer {
    val key = "$messageIdHex#$attachmentIndex"
    val initiallyAvailable =
        remember(controller, key) { controller.hasCachedAttachmentInMemory(messageIdHex, attachmentIndex) }
    val hostFlow =
        remember(controller, key, initiallyAvailable) {
            controller.attachmentTransferState(messageIdHex, attachmentIndex, initiallyAvailable)
        }
    DisposableEffect(controller, key, initiallyAvailable) {
        onDispose { controller.releaseAttachmentTransferState(messageIdHex, attachmentIndex) }
    }
    val host by hostFlow.collectAsStateWithLifecycle()
    val cancellation by remember(controller, key) {
        controller.attachmentCancellationState(messageIdHex, attachmentIndex)
    }.collectAsStateWithLifecycle()
    val materializing = observeNative && !mine
    val observing = tileObservesNative(observeNative, mine, host)
    val observed by remember(controller, key, reference.ciphertextSha256, reference.sourceEpoch, observing) {
        if (observing) controller.attachmentNativeProgress(messageIdHex, attachmentIndex) else emptyFlow()
    }.collectAsStateWithLifecycle(initialValue = null)
    // A sample outlives its flow, so once observation stops the last one would describe a transfer that is over.
    val native = if (observing) observed else null
    val state = attachmentFilePresentationState(tileHostState(host, native, materializing), native, cancellation)
    return remember(state, native, cancellation, suppressed, failedLocally, controller, key) {
        TileTransfer(state, native, cancellation, suppressed, failedLocally) {
            controller.cancelAttachmentTransfer(messageIdHex, attachmentIndex)
        }
    }
}

/**
 * The engine feed is opened only while this tile is materializing or another surface is downloading its attachment,
 * so idle tiles in a long conversation hold no subscription yet a shared download still shows its received bytes.
 * Own sends have no download to show.
 */
internal fun tileObservesNative(
    materializing: Boolean,
    mine: Boolean,
    host: AttachmentTransferState,
): Boolean = !mine && (materializing || host == AttachmentTransferState.Downloading)

/**
 * A tile restarts its download without the coordinator that publishes Downloading for a file card, so a Cancelled left
 * by the reader's earlier Cancel would hide the live transfer they restarted. While the tile is [materializing], a
 * stale Cancelled therefore reads as Remote until the engine confirms a cancellation of the running transfer. Once the
 * tile stops materializing the host's own answer stands, so a Cancel the reader just made shows as Cancelled.
 */
internal fun tileHostState(
    host: AttachmentTransferState,
    native: NativeAttachmentProgress?,
    materializing: Boolean,
): AttachmentTransferState =
    if (host == AttachmentTransferState.Cancelled &&
        materializing &&
        native?.phase != AttachmentTransferStateFfi.CANCELLED
    ) {
        AttachmentTransferState.Remote
    } else {
        host
    }

/** The spoken and visible name of the tile's current transfer step, never presenting ciphertext completion as ready. */
@Composable
internal fun tileTransferDescription(transfer: TileTransfer): String =
    when {
        transfer.cancelling -> stringResource(R.string.media_cancelling_download)
        transfer.cancellation == AttachmentCancellationState.Unconfirmed ->
            stringResource(R.string.media_cancel_unconfirmed)
        transfer.cancelled -> stringResource(R.string.media_download_cancelled)
        transfer.failed -> stringResource(R.string.media_tap_to_retry)
        else ->
            nativeProgressDescription(transfer.progress, transfer.state)
                ?: stringResource(R.string.media_downloading)
    }

/**
 * The tile's single transfer control in a fixed 52 dp slot: a progress ring with a Cancel glyph while a transfer is
 * queued or running, an indeterminate ring while Cancel awaits acknowledgement, Download again after a confirmed
 * Cancel and Retry after a failure. The whole slot is the target, so a tap on it never reaches the tile's own open
 * handler, including while Cancel awaits acknowledgement and the slot takes no action. With [showCaption] the step
 * and byte counts are also drawn for sighted readers, for tiles large enough.
 */
@Composable
internal fun TileTransferControl(
    transfer: TileTransfer,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showCaption: Boolean = false,
) {
    val description = tileTransferDescription(transfer)
    val cancelLabel = stringResource(R.string.media_cancel_download)
    val retryLabel = stringResource(R.string.media_tap_to_download)
    val action: (() -> Unit)? =
        when {
            transfer.cancelling -> null
            transfer.active -> transfer.onCancel
            else -> onRetry
        }
    val clickLabel = if (transfer.active) cancelLabel else retryLabel
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TileTransferCaptionGap),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(TILE_TRANSFER_CONTROL_SIZE)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE))
                    .then(
                        if (action != null) {
                            Modifier.clickable(
                                onClickLabel = clickLabel,
                                role = Role.Button,
                                onClick = action,
                            )
                        } else {
                            // Awaiting acknowledgement there is nothing to do, but the tap must not fall through to the
                            // tile's open handler and promote the transfer the reader just cancelled.
                            Modifier.pointerInput(Unit) { detectTapGestures { } }
                        },
                    ).semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            TileTransferGlyph(transfer, TILE_TRANSFER_CONTROL_SIZE, Color.White)
        }
        if (showCaption) TileTransferCaption(description)
    }
}

/**
 * The ring and glyph for the current step, drawn at [ringSize] in [color]: a determinate ring only while the engine
 * reports a trustworthy total, an indeterminate one otherwise, with Close while Cancel is available, a download arrow
 * after a confirmed Cancel and a refresh arrow after a failure.
 */
@Composable
internal fun TileTransferGlyph(
    transfer: TileTransfer,
    ringSize: Dp,
    color: Color,
    glyphSize: Dp = TileTransferGlyphSize,
) {
    if (transfer.active || transfer.cancelling) {
        val fraction = transfer.fraction
        if (fraction != null) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(ringSize - TileTransferRingInset),
                strokeWidth = TileTransferRingStroke,
                color = color,
                trackColor = color.copy(alpha = RING_TRACK_ALPHA),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(ringSize - TileTransferRingInset),
                strokeWidth = TileTransferRingStroke,
                color = color,
            )
        }
    }
    val icon =
        when {
            transfer.active || transfer.cancelling -> Icons.Default.Close
            transfer.cancelled -> Icons.Default.ArrowDownward
            else -> Icons.Default.Refresh
        }
    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(glyphSize))
}

/** Step and byte text for sighted readers; the spoken description lives on the control itself. */
@Composable
private fun TileTransferCaption(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .clip(RoundedCornerShape(TileTransferCaptionCorner))
                .background(Color.Black.copy(alpha = ScrimAlpha.AFFORDANCE))
                .padding(horizontal = TileTransferCaptionPadding, vertical = TileTransferCaptionPadding / 2),
    )
}

private const val RING_TRACK_ALPHA = 0.3f
