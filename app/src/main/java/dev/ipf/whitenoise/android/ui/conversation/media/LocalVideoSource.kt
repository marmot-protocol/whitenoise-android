package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import dev.ipf.whitenoise.android.media.sandbox.IsolatedAttachmentPlayer

/** Only selected local capabilities may enter the attachment decoder; suffixes never enable network playlists. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun localVideoPlayer(
    context: Context,
    uri: Uri,
): IsolatedAttachmentPlayer? = if (isLocalVideoUri(uri)) IsolatedAttachmentPlayer(context, uri) else null

internal fun isLocalVideoUri(uri: Uri): Boolean = uri.scheme == "file" || uri.scheme == "content"
