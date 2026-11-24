@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.voicePlaybackSource
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.validatedAttachmentCacheFile
import dev.ipf.whitenoise.android.ui.conversation.media.voicePlaybackKey
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.ArrowDownward
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Voice tab of the library. */
@Composable
internal fun VoiceLibraryTab(
    tiles: SharedMediaTiles,
    listState: androidx.compose.foundation.lazy.LazyListState,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onJumpToMessage: (String) -> Unit,
) {
    MonthSectionedColumn(
        sections = tiles.voiceSections,
        listState = listState,
        emptyLabel = stringResource(R.string.shared_media_empty_voice),
        keyOf = { "${it.messageIdHex}#${it.attachmentIndex}" },
        messageIdOf = { it.messageIdHex },
        onJumpToMessage = onJumpToMessage,
    ) { row ->
        VoiceLibraryRow(
            row = row,
            controller = controller,
            appState = appState,
            onJumpToMessage = onJumpToMessage,
        )
    }
}

/**
 * The Shared media voice control's glyph: progress while loading, Download until the clip is on the device, then Play
 * or Pause, so the control never announces an action its tap does not perform.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
private fun VoiceLibraryActionIcon(
    loading: Boolean,
    playable: Boolean,
    playing: Boolean,
) {
    Box(contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else if (!playable) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = stringResource(R.string.media_tap_to_download),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(
                painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow),
                contentDescription =
                    stringResource(
                        if (playing) R.string.voice_message_pause else R.string.voice_message_play,
                    ),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Whether a Shared media clip is on the device, or null until the cache probe answers. The reader's own clip still
 * held in memory for its upload counts at once, as it does for a file row.
 */
@Composable
private fun rememberLibraryClipOnDevice(
    controller: ConversationController,
    row: SharedMediaRow,
    pillKey: String,
): Boolean? {
    val retainedOwnClip =
        remember(pillKey, row.mine) {
            row.mine &&
                controller
                    .pendingAttachmentsList(row.messageIdHex)
                    .getOrNull(row.attachmentIndex)
                    ?.inMemoryBytes != null
        }
    val onDevice by produceState<Boolean?>(initialValue = true.takeIf { retainedOwnClip }, pillKey, retainedOwnClip) {
        value = retainedOwnClip || controller.hasCachedAttachmentAfterHydration(row.messageIdHex, row.attachmentIndex)
    }
    return onDevice
}

/**
 * Checks a Shared media clip at tap time, off the main thread. Returns the file the cache still holds, or null once
 * it was evicted, and whether the clip is on the device: an evicted file also outdates the earlier cache probe, so
 * that probe counts only while nothing had been loaded yet.
 */
private suspend fun libraryClipAtTap(
    loaded: java.io.File?,
    probedOnDevice: Boolean,
): Pair<java.io.File?, Boolean> {
    val current = loaded?.let { withContext(Dispatchers.IO) { validatedAttachmentCacheFile(it) } }
    return current to (current != null || (loaded == null && probedOnDevice))
}

/** One voice row with playback. */
@Composable
@Suppress("LongMethod")
internal fun VoiceLibraryRow(
    row: SharedMediaRow,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    onJumpToMessage: (String) -> Unit,
) {
    val playbackTitleCopy =
        dev.ipf.whitenoise.android.ui.common
            .rememberGroupTitleCopy()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey =
        voicePlaybackKey(
            row.messageIdHex,
            row.attachmentIndex,
            row.reference.sourceEpoch,
        )
    var localFile by remember(pillKey) { mutableStateOf<java.io.File?>(null) }
    var loading by remember(pillKey) { mutableStateOf(false) }
    // The control stays hidden and inert until the cache probe answers, so a cached clip never flashes Download; it
    // then offers Download until the clip is local, and Play after.
    val onDevice = rememberLibraryClipOnDevice(controller, row, pillKey)
    val playable = onDevice == true || localFile != null
    val actionReady = onDevice != null || localFile != null

    val isPlayingThis by remember(pillKey) {
        VoicePlaybackController.state
            .map { playback -> playback.key == pillKey && playback.isPlaying }
            .distinctUntilChanged()
    }.collectAsState(false)
    val recordedAtLabel = rememberRelativeTimestamp(row.recordedAt)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(rememberMediaLibraryRowSurface())
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .amoledSurfaceBorder(MaterialTheme.shapes.large)
                .clickable { onJumpToMessage(row.messageIdHex) }
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Play/pause control. Reuses the process-wide VoicePlaybackController so
        // starting a clip here pauses any clip playing elsewhere (single
        // playback). The tap target is isolated from the row's jump-to-message
        // click so the two affordances don't collide.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = CircleShape,
            modifier =
                Modifier
                    .size(48.dp)
                    .clickable(enabled = !loading && actionReady) {
                        if (isPlayingThis) {
                            VoicePlaybackController.pause()
                            return@clickable
                        }
                        // A clip that is not on the device yet is only downloaded; the next tap plays it. A file the
                        // cache evicted since it was loaded is checked again here, so it is downloaded, not played.
                        val probedOnDevice = onDevice == true
                        scope.launch {
                            val (current, downloaded) = libraryClipAtTap(localFile, probedOnDevice)
                            localFile = current
                            val file =
                                localFile ?: runCatching {
                                    loading = true
                                    materializeVoiceAttachment(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = row.messageIdHex,
                                        attachmentIndex = row.attachmentIndex,
                                        reference = row.reference,
                                        mine = row.mine,
                                    )
                                }.onFailure { error ->
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    appState.presentFailure(
                                        R.string.shared_media_voice_failed,
                                        "MEDIA_LIBRARY_VOICE_LOAD",
                                        error,
                                    )
                                }.also { loading = false }
                                    .getOrNull() ?: return@launch
                            localFile = file
                            if (!downloaded) return@launch
                            val source =
                                controller.voicePlaybackSource(
                                    appState,
                                    row.messageIdHex,
                                    playbackTitleCopy,
                                ) ?: return@launch
                            VoicePlaybackController.play(pillKey, file, source = source)
                        }
                    },
        ) {
            if (actionReady) VoiceLibraryActionIcon(loading = loading, playable = playable, playing = isPlayingThis)
        }
        SenderAvatar(sender = row.sender, appState = appState)
        Column(modifier = Modifier.weight(1f)) {
            EmojiLabel(
                appState.displayName(row.sender),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Voice duration isn't carried on the imeta reference and probing it
            // requires decoding the clip, which would force an auto-download
            // here; the send timestamp stands in until playback materializes the
            // file. follow-up: surface duration once the file is local.
            Text(
                recordedAtLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Displays a media sender through the active viewer's local contact-picture choice. */
@Composable
private fun SenderAvatar(
    sender: String,
    appState: WhiteNoiseAppState,
) {
    Avatar(
        title = appState.displayName(sender),
        seed = sender,
        size = 36.dp,
        pictureUrl = appState.contactAvatarSource(sender),
        pictureDomain = AvatarImageLoader.publicProfiles(),
        followedAccount = sender,
    )
}
