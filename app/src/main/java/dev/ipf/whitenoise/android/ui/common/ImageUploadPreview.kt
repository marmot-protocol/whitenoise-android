package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.media.MediaPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberImageUploadPreview(draft: ImageUploadDraft?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = draft) {
        value =
            if (draft == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    MediaPipeline
                        .decodeSampledBitmap(draft.plaintext, maxEdgePx = 512)
                        ?.asImageBitmap()
                }
            }
    }
    return image
}
