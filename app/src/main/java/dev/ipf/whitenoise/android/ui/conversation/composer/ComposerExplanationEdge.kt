package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import dev.ipf.whitenoise.android.ui.common.LocalExplanationBottomEdge
import dev.ipf.whitenoise.android.ui.common.LocalExplanationHost
import dev.ipf.whitenoise.android.ui.design.platformBackCallbacksEnabled
import kotlin.math.roundToInt

/** The composer's top edge while [ExplainAboveComposer] is active, or `null` outside it. */
private val LocalComposerTopEdge = staticCompositionLocalOf<ComposerTopEdge?> { null }

/**
 * Explains the held controls in [content] above the composer, keeping the keyboard, draft and selection as they
 * are. The app's modal explanation sheet opens a focusable window, which takes focus from the editor and closes the
 * keyboard. The composer marks its visible surface with [composerExplanationEdge], whose top the sheet stays above.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ExplainAboveComposer(content: @Composable () -> Unit) {
    val edge = remember { ComposerTopEdge() }
    val bottomEdge = remember(edge) { { edge.windowY } }
    CompositionLocalProvider(
        LocalExplanationBottomEdge provides bottomEdge,
        LocalComposerTopEdge provides edge,
        content = content,
    )
}

/**
 * Records this element's top as the composer's top edge, in the window coordinates popups are placed in. The
 * composer marks its visible surface, which holds the reply preview, attachments and status rows above the editor,
 * so an explanation never covers them. It does not mark its own root: in a short landscape window with the keyboard
 * open, the root reaches far above the surface.
 */
@Composable
internal fun Modifier.composerExplanationEdge(): Modifier {
    val edge = LocalComposerTopEdge.current ?: return this
    return onGloballyPositioned { edge.windowY = it.positionInWindow().y.roundToInt() }
}

/**
 * Closes the explanation open above the composer, or `null` while none is. The editor's Back routing treats it like
 * the composer's other popups: Back closes the explanation and leaves the keyboard open.
 */
@Composable
internal fun composerExplanationClose(): (() -> Unit)? {
    val explanations = LocalExplanationHost.current ?: return null
    return if (explanations.shown != null && explanations.bottomEdge != null) explanations::dismiss else null
}

/**
 * Whether the editor's pre-IME Back is on. An open explanation always closes there: on Android 14 and 15 a Back key
 * reaches the keyboard before the app's overlay-priority callback, and Android 13 drops that callback altogether, so
 * Back would hide the keyboard instead. The sending-identity popup keeps its overlay-priority Back owner where
 * [platformBackCallbacksEnabled], and needs this catch only below that. With no popup open, the catch hides the
 * keyboard or leaves the conversation as before.
 */
internal fun preImeHandlesBack(
    closeSendingIdentity: (() -> Unit)?,
    closeExplanation: (() -> Unit)?,
): Boolean =
    closeExplanation != null ||
        closeSendingIdentity == null ||
        !platformBackCallbacksEnabled()

/** The composer's laid-out top edge in window pixels, observed by an open explanation's sheet so it follows along. */
private class ComposerTopEdge {
    var windowY by mutableIntStateOf(0)
}
