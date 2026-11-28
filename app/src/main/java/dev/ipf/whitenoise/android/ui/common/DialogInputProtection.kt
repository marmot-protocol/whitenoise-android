@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.common

import android.os.Build
import android.view.MotionEvent
import android.view.Window
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import dev.ipf.whitenoise.android.R

/** Keeps overlays away from a sensitive dialog and rejects both fully and partly obscured gestures. */
@Composable
internal fun ProtectDialogInput() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val context = LocalContext.current
    DisposableEffect(window, context) {
        val original = window?.callback
        val guarded =
            original?.let {
                ObscuredTouchGuard(it) {
                    Toast.makeText(context, R.string.link_touch_blocked, Toast.LENGTH_SHORT).show()
                }
            }
        if (window != null && guarded != null) {
            window.callback = guarded
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        }
        onDispose {
            if (window != null && window.callback === guarded) {
                window.callback = original
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(false)
            }
        }
    }
}

private class ObscuredTouchGuard(
    private val delegate: Window.Callback,
    private val onObscured: () -> Unit,
) : Window.Callback by delegate {
    private var blocked = false
    private var warned = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val isDown = event.actionMasked == MotionEvent.ACTION_DOWN
        if (isDown) blocked = false
        if (blocked) return true
        val obscuredFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        return if (event.flags and obscuredFlags == 0) {
            delegate.dispatchTouchEvent(event)
        } else {
            blocked = true
            if (!warned) {
                warned = true
                onObscured()
            }
            if (!isDown) {
                val cancel = MotionEvent.obtain(event)
                try {
                    cancel.action = MotionEvent.ACTION_CANCEL
                    delegate.dispatchTouchEvent(cancel)
                } finally {
                    cancel.recycle()
                }
            }
            true
        }
    }
}
