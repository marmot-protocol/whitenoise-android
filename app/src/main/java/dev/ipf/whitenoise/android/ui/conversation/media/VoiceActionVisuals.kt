package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowDownward

/** Stable action variants keep accessibility copy and glyph selection in lockstep. */
internal enum class VoiceActionVisual(
    val descriptionResource: Int,
) {
    LOADING(R.string.media_downloading),
    FAILED(R.string.voice_message_failed),
    STORAGE_BLOCKED(R.string.media_storage_blocked),
    DOWNLOAD(R.string.media_tap_to_download),
    PAUSE(R.string.voice_message_pause),
    PLAY(R.string.voice_message_play),
}

/**
 * Resolves one action variant without coupling presentation to materialization owners. A clip that is not on the
 * device yet never reads as Play: it offers Download, or shows loading once its download is accepted.
 */
internal fun voiceActionVisual(
    loading: Boolean,
    failed: Boolean,
    startDownload: Boolean,
    localFileAvailable: Boolean,
    isPlaying: Boolean,
    storageBlocked: Boolean = false,
): VoiceActionVisual =
    when {
        loading -> VoiceActionVisual.LOADING
        failed && storageBlocked -> VoiceActionVisual.STORAGE_BLOCKED
        failed -> VoiceActionVisual.FAILED
        !startDownload && !localFileAvailable -> VoiceActionVisual.DOWNLOAD
        !localFileAvailable -> VoiceActionVisual.LOADING
        isPlaying -> VoiceActionVisual.PAUSE
        else -> VoiceActionVisual.PLAY
    }

/** The 24dp glyph inside the play button for each action variant. */
@Suppress("FunctionNaming")
@Composable
internal fun VoiceActionGlyph(actionVisual: VoiceActionVisual) {
    when (actionVisual) {
        VoiceActionVisual.LOADING ->
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
        VoiceActionVisual.FAILED,
        VoiceActionVisual.STORAGE_BLOCKED,
        ->
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(24.dp))
        VoiceActionVisual.DOWNLOAD ->
            Icon(imageVector = Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(24.dp))
        VoiceActionVisual.PAUSE ->
            Icon(
                painter = painterResource(R.drawable.ic_pause),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        VoiceActionVisual.PLAY ->
            Icon(
                painter = painterResource(R.drawable.ic_play_arrow),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
    }
}

/** What the voice row's one 48 dp action looks, sounds and does like for the current step. */
internal class VoiceAction(
    val description: String,
    val clickLabel: String?,
    val onClick: () -> Unit,
    /** Non-null while the transfer owns the action, so the row draws its ring and text instead of play. */
    val transfer: TileTransfer?,
)

/**
 * Resolves the action: a visible [transfer] owns it (Cancel while active, nothing while Cancel awaits acknowledgement,
 * Retry or Download again otherwise), and otherwise it is the ordinary play, pause or download action.
 */
@Composable
internal fun voiceAction(
    transfer: TileTransfer?,
    visual: VoiceActionVisual,
    onActionClick: () -> Unit,
    onRetryTransfer: () -> Unit,
): VoiceAction {
    if (transfer == null || !transfer.visible) {
        return VoiceAction(stringResource(visual.descriptionResource), null, onActionClick, null)
    }
    val onClick =
        when {
            transfer.cancelling -> ({})
            transfer.active -> transfer.onCancel
            else -> onRetryTransfer
        }
    val label = if (transfer.active) stringResource(R.string.media_cancel_download) else null
    return VoiceAction(tileTransferDescription(transfer), label, onClick, transfer)
}
