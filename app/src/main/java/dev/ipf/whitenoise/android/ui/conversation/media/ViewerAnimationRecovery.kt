package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.whitenoise.android.media.AnimationTermination
import dev.ipf.whitenoise.android.media.PreemptibleAnimation

/** One automatic attempt per source owner; the cause of system-confirmed worker death remains unknown. */
internal class ViewerAnimationRecovery {
    private var spent = false

    fun claim(reason: AnimationTermination): Boolean {
        if (reason != AnimationTermination.WorkerLostUnknown || spent) return false
        spent = true
        return true
    }
}

/** Healthy pauses retain their clock. A terminal reason alone never proves transport/protocol retirement. */
@Composable
@Suppress("FunctionNaming") // Compose effects follow the existing composable naming convention.
internal fun ObserveViewerAnimationTermination(
    sourceKey: Any,
    drawable: Drawable?,
    active: Boolean,
    onTerminated: (Drawable, AnimationTermination) -> Unit,
) {
    val latestOnTerminated by rememberUpdatedState(onTerminated)
    LaunchedEffect(sourceKey, drawable, active) {
        if (!active) return@LaunchedEffect
        val produced = drawable ?: return@LaunchedEffect
        val observed = produced as? PreemptibleAnimation ?: return@LaunchedEffect
        val reason = observed.awaitTermination()
        latestOnTerminated(produced, reason)
    }
}
