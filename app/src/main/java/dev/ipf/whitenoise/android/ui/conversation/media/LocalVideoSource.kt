package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

/** Received and picked files cannot select a network playlist or streaming protocol by their suffix. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun localVideoSource(
    context: Context,
    uri: Uri,
): MediaSource? {
    if (!isLocalVideoUri(uri)) return null
    return ProgressiveMediaSource
        .Factory(DefaultDataSource.Factory(context))
        .createMediaSource(MediaItem.fromUri(uri))
}

internal fun isLocalVideoUri(uri: Uri): Boolean = uri.scheme == "file" || uri.scheme == "content"
