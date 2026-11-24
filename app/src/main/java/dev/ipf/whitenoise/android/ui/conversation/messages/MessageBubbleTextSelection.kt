package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import dev.ipf.whitenoise.android.ui.markdownHasLinkAnnotationAt

/**
 * One bubble's text selection entry and rendered-text hit testing: entering text selection, registering the plain
 * text leaf for selection, and telling ordinary text apart from links and empty row space.
 *
 * An instance lasts as long as the bubble keeps its [textLayouts], which is the only value the replaced local
 * functions captured that stays fixed for a mounted message. Every other value comes from [latest], the most recent
 * composition that used this instance, read once when [beginTextSelection] runs. Once a new record id replaces
 * [textLayouts], a callback still holding this instance keeps the values from its own lifetime.
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
 * no snapshot read to composition and a new menu or selection callback never needs a new instance. It is updated
 * on every composition like `rememberUpdatedState`, but in a state remembered on the same key as the instance, so
 * a replaced instance, such as one a queued menu action still holds after a pending send confirms, never sees the
 * confirmed message's values.
 */
@Composable
internal fun rememberMessageBubbleTextSelection(
    textLayouts: MessageBubbleTextLayouts,
    inputs: MessageBubbleTextSelection.Inputs,
): MessageBubbleTextSelection {
    val latest = remember(textLayouts) { mutableStateOf(inputs) }
    latest.value = inputs
    return remember(textLayouts) {
        MessageBubbleTextSelection(
            textLayouts = textLayouts,
            latest = latest,
        )
    }
}
