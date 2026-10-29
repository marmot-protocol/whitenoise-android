package dev.ipf.whitenoise.android.ui.common

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.platform.app.InstrumentationRegistry

/** Sends Android Back through PopupLayout, which owns dismissal outside the inner Compose key pipeline. */
internal fun SemanticsNodeInteraction.dispatchNativePopupBack() {
    val popup = checkNotNull(fetchSemanticsNode().root as? View).rootView
    check(popup.javaClass.name == "androidx.compose.ui.window.PopupLayout")
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        check(popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        check(popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
    }
}
