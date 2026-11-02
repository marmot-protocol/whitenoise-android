package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction

/**
 * Captures the actual production onClick before disposal. The pinned Compose runtime wraps semantics clicks
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
