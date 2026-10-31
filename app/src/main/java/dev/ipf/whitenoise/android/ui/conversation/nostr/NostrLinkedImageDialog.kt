@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.automirrored.outlined.ArrowBack

/** Created only by a user's media-link action, including its normal labelled-link confirmation. */
@Composable
internal fun NostrLinkedImageDialog(
    url: String,
    onDismiss: () -> Unit,
    // A Nostr event's image is chosen by its author and host, never by a member, so it decodes single-use.
    loadImage: suspend (String, Int) -> ImageBitmap? = { url, width ->
        AvatarImageLoader.retryBanner(url, width, dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique)
    },
) {
    PlaybackDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                NostrEventImagePane(
                    url = url,
                    loadImage = loadImage,
                    initiallyRequested = true,
                    onFullscreenDismiss = onDismiss,
                )
            }
        }
    }
}
