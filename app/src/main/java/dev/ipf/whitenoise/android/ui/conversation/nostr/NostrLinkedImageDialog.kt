@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.nostr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.conversation.media.AttachmentViewerChrome

/**
 * Created only by a user's media-link action, including its normal labelled-link confirmation. It leads with the
 * shared viewer chrome's Close, like every other viewer.
 */
@Composable
internal fun NostrLinkedImageDialog(
    url: String,
    onDismiss: () -> Unit,
    // A Nostr event's image is chosen by its author and host, never by a member, so it decodes single-use.
    loadImage: suspend (String, Int) -> ImageBitmap? = { url, width ->
        AvatarImageLoader.retryBanner(url, width, dev.ipf.whitenoise.android.media.MediaTrustDomain.Unique)
    },
) {
    MeasuredPlaybackDialog(
        origin = null,
        ownerKey = url to AvatarImageLoader.currentCacheLifetime(),
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                AttachmentViewerChrome(
                    title = stringResource(R.string.attachment_type_image),
                    onClose = dismiss,
                    // The parent pads the system bars; this adds only a cutout it leaves uncovered.
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                NostrEventImagePane(
                    url = url,
                    loadImage = loadImage,
                    initiallyRequested = true,
                    onFullscreenDismiss = {
                        // The inner viewer already completed its exit; retire this source without another fade.
                        cancelCompletion()
                        onDismiss()
                    },
                )
            }
        }
    }
}
