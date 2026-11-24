package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import dev.ipf.whitenoise.android.ui.markdownHasLinkAnnotationAt

/**
 * One bubble's text selection entry and rendered-text hit testing: entering text selection, registering the plain
 * text leaf for selection, and telling ordinary text apart from links and empty row space.
 *
 * An instance lasts as long as the bubble keeps its [textLayouts], which is the only value the replaced local
 * functions captured that stays fixed for a mounted message. Every other value comes from [latest], the bubble's
 * most recent composition, read once when [beginTextSelection] runs.
 */
internal class MessageBubbleTextSelection(
    private val textLayouts: MessageBubbleTextLayouts,
    private val latest: State<Inputs>,
) {
    /** The values one composition of the bubble computed for entering text selection, compared by identity. */
    internal class Inputs(
        val deleted: Boolean,
        val onActionMenuOpenChange: (Boolean) -> Unit,
        val onTextSelectionModeChange: (Boolean) -> Unit,
    )

    /** Registers the plain-text leaf for selection once both its layout and its position are known. */
    fun reportPlainTextLayoutIfReady() {
        val layoutResult = textLayouts.plainTextLayoutTracker.layoutResult ?: return
        val coordinates = textLayouts.plainTextLayoutTracker.coordinates ?: return
        textLayouts.selectableTextLayouts.update(textLayouts.plainTextLayoutKey, layoutResult, coordinates)
    }

    /** Enters text selection for this message with a cleared layout registry, closing the action menu. */
    fun beginTextSelection() {
        with(latest.value) {
            if (deleted) return
            textLayouts.selectableTextLayouts.clear()
            textLayouts.textSelectionSeeded = false
            onActionMenuOpenChange(false)
            onTextSelectionModeChange(true)
        }
    }

    /**
     * Whether a press lands on this bubble's rendered text rather than a link, the row's empty width or its
     * chrome, so a double tap there may edit without stealing a link's own activation.
     */
    fun ordinaryTextHitAt(pressInWindow: Offset): Boolean =
        !markdownHasLinkAnnotationAt(textLayouts.markdownLinkLayouts.values, pressInWindow) &&
            renderedTextHitAtWindowPosition(
                textLayouts.selectableTextLayouts.snapshot(),
                pressInWindow,
                requireTextLineHit = true,
            ) != null
}

/**
 * Remembers [MessageBubbleTextSelection] for the bubble's [textLayouts]. [inputs] is this composition's snapshot of
 * the other captured values and is only read when [MessageBubbleTextSelection.beginTextSelection] runs, so it adds
 * no snapshot read to composition and a new menu or selection callback never needs a new instance.
 */
@Composable
internal fun rememberMessageBubbleTextSelection(
    textLayouts: MessageBubbleTextLayouts,
    inputs: MessageBubbleTextSelection.Inputs,
): MessageBubbleTextSelection {
    val latest = rememberUpdatedState(inputs)
    return remember(textLayouts) {
        MessageBubbleTextSelection(
            textLayouts = textLayouts,
            latest = latest,
        )
    }
}
