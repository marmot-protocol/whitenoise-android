package dev.ipf.whitenoise.android.ui.conversation.media

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.OneShotPreDrawListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Binds the dialog's content-owned motion and first-frame admission without changing a host window. */
@Composable
internal fun rememberMediaViewerEntryFrame(
    enabled: Boolean,
    sourceAvailable: Boolean,
): suspend () -> Unit {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    if (enabled && sourceAvailable && window != null) {
        // Keep the exit animation disabled too: the content owns the complete return transition.
        SideEffect {
            if (window.attributes.windowAnimations != 0) window.setWindowAnimations(0)
        }
    }
    return remember(view, window) {
        suspend { if (window != null) awaitMediaViewerEntryFrame(view) }
    }
}

/**
 * Holds thumbnail travel until the new dialog has submitted its first frame, instead of starting
 * its animation clock during window creation. Software-rendered hosts proceed after pre-draw;
 * they cannot deliver Android's hardware frame-commit callback.
 */
internal suspend fun awaitMediaViewerEntryFrame(view: View) {
    suspendCancellableCoroutine<Unit> { continuation ->
        var committedObserver: ViewTreeObserver? = null
        val committed = Runnable { if (continuation.isActive) continuation.resume(Unit) }
        val preDraw =
            OneShotPreDrawListener.add(view) {
                if (view.isHardwareAccelerated) {
                    committedObserver = view.viewTreeObserver
                    committedObserver?.registerFrameCommitCallback(committed)
                } else {
                    committed.run()
                }
            }
        continuation.invokeOnCancellation {
            val remove =
                Runnable {
                    preDraw.removeListener()
                    committedObserver?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(committed)
                }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                remove.run()
            } else {
                Handler(Looper.getMainLooper()).post(remove)
            }
        }
        view.postInvalidateOnAnimation()
    }
}
