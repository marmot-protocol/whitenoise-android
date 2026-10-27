package dev.ipf.whitenoise.android.ui.conversation.media

import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Native Robolectric dialog fixtures do not submit a real hardware window frame. With the Compose
 * clock paused, publish the dialog, deliver its pre-draw/commit signal, then finish the real motion.
 * The production admission remains active; this supplies the renderer event absent from the host.
 */
internal fun ComposeContentTestRule.submitMediaViewerDialogFrame() {
    check(!mainClock.autoAdvance)
    runOnUiThread { Snapshot.sendApplyNotifications() }
    waitForIdle()
    mainClock.advanceTimeByFrame()
    waitForIdle()
    runOnUiThread {
        val dialog =
            WindowInspector.getGlobalWindowViews().single {
                (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION
            }
        val observer = dialog.viewTreeObserver
        observer.dispatchOnPreDraw()
        captureDialogCommits(observer).forEach { it.run() }
    }
    mainClock.advanceTimeBy(300)
    waitForIdle()
}

/** Mirrors the renderer taking this frame's commit callbacks in a native dialog test. */
@Suppress("UNCHECKED_CAST")
private fun captureDialogCommits(observer: ViewTreeObserver): List<Runnable> {
    val method = ViewTreeObserver::class.java.getDeclaredMethod("captureFrameCommitCallbacks")
    method.isAccessible = true
    return method.invoke(observer) as? List<Runnable> ?: emptyList()
}
