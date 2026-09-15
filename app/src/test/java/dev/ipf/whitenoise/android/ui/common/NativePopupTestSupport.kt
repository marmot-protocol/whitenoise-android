package dev.ipf.whitenoise.android.ui.common

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
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

/**
 * Captures the actual production onClick before disposal. Pinned Compose 1.12 wraps semantics clicks
 * with an attached-node sound lookup; replaying that framework wrapper never reaches our owner guard.
 * Reflective shape checks fail loudly on a framework change instead of accepting a detached-node error.
 */
internal fun SemanticsNodeInteraction.captureClickCallbackForReplay(): () -> Unit {
    val semanticsClick = checkNotNull(fetchSemanticsNode().config[SemanticsActions.OnClick].action)
    val clickableClass = Class.forName("androidx.compose.foundation.AbstractClickableNode")
    val nodeField = semanticsClick.javaClass.declaredFields.single { clickableClass.isAssignableFrom(it.type) }
    nodeField.isAccessible = true
    val clickableNode = nodeField.get(semanticsClick)
    val callbackField = clickableClass.getDeclaredField("onClick").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") // The pinned node field is Function0<Unit>; class/field checks above are exact.
    return callbackField.get(clickableNode) as () -> Unit
}
