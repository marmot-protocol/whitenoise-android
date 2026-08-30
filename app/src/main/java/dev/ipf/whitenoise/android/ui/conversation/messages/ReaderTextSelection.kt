@file:Suppress(
    "FunctionNaming", // Compose UI functions intentionally use PascalCase.
    "MatchingDeclarationName", // The controller and its Compose adapters form one selection unit.
)

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import dev.ipf.whitenoise.android.ui.MarkdownLinkTextLayout
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt

/**
 * Activates Compose's native selection only after a reader long-press. Keeping
 * the container dormant until then lets the reader's vertical scroll and link
 * taps retain their normal gesture ownership.
 */
internal class ReaderTextSelectionController(
    val selectionState: SelectionState,
) {
    // Layout coordinates update during every scroll frame. Plain registries
    // keep those updates out of snapshot state; only a pending activation
    // advances [layoutRevision] so the delayed native-selection seed retries.
    private val selectableLayouts = linkedMapOf<Any, SelectableTextLayout>()
    private val markdownLinkLayouts = linkedMapOf<Any, MarkdownLinkTextLayout>()

    var layoutRevision by mutableIntStateOf(0)
        private set

    var active by mutableStateOf(false)
        private set

    var pendingPosition by mutableStateOf<Offset?>(null)
        private set

    val selectableTextLayoutReporter:
        (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit = { key, layoutResult, coordinates ->
            if (layoutResult != null && coordinates != null) {
                selectableLayouts[key] = SelectableTextLayout(key, layoutResult, coordinates)
            } else {
                selectableLayouts.remove(key)
            }
            if (active && pendingPosition != null) layoutRevision++
        }

    val markdownLinkLayoutReporter:
        (Any, AnnotatedString, TextLayoutResult?, LayoutCoordinates?) -> Unit =
        { key, text, layoutResult, coordinates ->
            if (layoutResult != null && coordinates != null) {
                markdownLinkLayouts[key] = MarkdownLinkTextLayout(text, layoutResult, coordinates)
            } else {
                markdownLinkLayouts.remove(key)
            }
            if (active && pendingPosition != null) layoutRevision++
        }

    fun requestSelection(position: Offset): Boolean {
        val hasText = textSelectionSeedRange(selectableLayouts.values, position) != null
        val hasLink = markdownLinkDestinationAt(markdownLinkLayouts.values, position) != null
        if (!hasText && !hasLink) return false
        pendingPosition = position
        active = true
        return true
    }

    fun seedPendingSelection() {
        val position = pendingPosition ?: return
        textSelectionSeedRange(selectableLayouts.values, position)?.let { range ->
            selectionState.select(range)
            pendingPosition = null
        }
    }

    fun selectedText(fullText: String): String =
        if (active) {
            selectionState.selectedTexts
                .joinToString(separator = "\n", transform = AnnotatedString::text)
                .ifEmpty { fullText }
        } else {
            fullText
        }

    fun reset() {
        active = false
        pendingPosition = null
    }
}

@Composable
internal fun rememberReaderTextSelectionController(
    contentKey: Any,
    existing: ReaderTextSelectionController? = null,
): ReaderTextSelectionController {
    if (existing != null) return existing
    val selectionState = rememberSelectionState()
    val controller = remember(contentKey, selectionState) { ReaderTextSelectionController(selectionState) }
    val layoutRevision = controller.layoutRevision
    val selectedTexts = controller.selectionState.selectedTexts

    LaunchedEffect(controller.active, layoutRevision, controller.pendingPosition) {
        if (!controller.active || controller.pendingPosition == null) return@LaunchedEffect
        // SelectionState can only resolve a range after the newly mounted
        // SelectionContainer has registered the reader's selectable leaves.
        withFrameNanos { }
        controller.seedPendingSelection()
    }
    LaunchedEffect(controller.active, controller.pendingPosition, selectedTexts) {
        // Native Copy dismisses the platform selection. Mirror that dismissal
        // in the dormant/active wrapper so the next Back closes the reader
        // instead of consuming a stale selection session.
        if (controller.active && controller.pendingPosition == null && selectedTexts.isEmpty()) {
            controller.reset()
        }
    }
    return controller
}

@Composable
internal fun Modifier.readerTextSelectionLongPress(
    contentKey: Any,
    onLongPress: (Offset) -> Boolean,
): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }
        .pointerInput(contentKey) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = awaitLongPressOrCancellation(down.id)
                val position = press?.let { coordinates?.localToWindow(it.position) }
                if (press != null && position != null && onLongPress(position)) {
                    press.consume()
                    consumePointerInputUntilReleased(down.id)
                }
            }
        }
}

@Composable
internal fun ReaderSelectablePlainText(
    text: String,
    onSelectableTextLayoutChanged: (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit,
) {
    val key = remember(text) { Any() }
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var coordinates by remember(text) { mutableStateOf<LayoutCoordinates?>(null) }

    fun reportIfReady() {
        val layout = layoutResult ?: return
        val layoutCoordinates = coordinates ?: return
        onSelectableTextLayoutChanged(key, layout, layoutCoordinates)
    }

    DisposableEffect(key, onSelectableTextLayoutChanged) {
        onDispose { onSelectableTextLayoutChanged(key, null, null) }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    coordinates = it
                    reportIfReady()
                },
        onTextLayout = {
            layoutResult = it
            reportIfReady()
        },
    )
}
