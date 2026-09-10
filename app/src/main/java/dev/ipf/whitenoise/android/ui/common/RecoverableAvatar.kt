package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import kotlinx.coroutines.flow.first

/**
 * Keeps a missing avatar eligible for one attempt per recovery event while visible.
 * Call inside the avatar's identity key so a replacement cannot retain old pixels.
 */
@Composable
internal fun rememberRecoverableAvatar(
    initialImage: ImageBitmap?,
    enabled: Boolean = true,
    load: suspend () -> ImageBitmap?,
): State<ImageBitmap?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLoad by rememberUpdatedState(load)
    return produceState(initialImage, lifecycle, enabled) {
        if (!enabled || value != null) return@produceState
        // Retain completed attempts across STOP/START, but not a cancelled wait.
        var completedRevision: Long? = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AvatarLoadRecovery.revision.first { revision ->
                if (value == null && completedRevision != revision) {
                    value = currentLoad()
                    // Consume the captured event, not a newer event received during the load.
                    completedRevision = revision
                }
                value != null
            }
        }
    }
}
