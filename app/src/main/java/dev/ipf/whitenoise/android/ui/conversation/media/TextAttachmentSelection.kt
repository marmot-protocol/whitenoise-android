@file:Suppress(
    "FunctionNaming", // Compose UI functions intentionally use PascalCase.
    "MatchingDeclarationName", // The controller and its Compose adapters form one selection unit.
)

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.conversation.messages.SelectableTextLayout
import dev.ipf.whitenoise.android.ui.conversation.messages.consumePointerInputUntilReleased
import dev.ipf.whitenoise.android.ui.conversation.messages.textSelectionSeedRange
import dev.ipf.whitenoise.android.ui.markdownLinkDestinationAt

internal class TextAttachmentSelectionController(
    val selectionState: SelectionState,
) {
    private val selectableLayouts = mutableStateMapOf<Any, SelectableTextLayout>()
    private val markdownLinkLayouts = mutableStateMapOf<Any, MarkdownLinkTextLayout>()

    var active by mutableStateOf(false)
        private set

    var pendingPosition by mutableStateOf<Offset?>(null)
        private set

    val selectableLayoutSnapshot: List<SelectableTextLayout>
        get() = selectableLayouts.values.toList()

    val selectableTextLayoutReporter:
        (Any, TextLayoutResult?, LayoutCoordinates?) -> Unit = { key, layoutResult, coordinates ->
            if (layoutResult != null && coordinates != null) {
                selectableLayouts[key] = SelectableTextLayout(key, layoutResult, coordinates)
            } else {
                selectableLayouts.remove(key)
            }
        }

    val markdownLinkLayoutReporter:
        (Any, AnnotatedString, TextLayoutResult?, LayoutCoordinates?) -> Unit =
        { key, text, layoutResult, coordinates ->
            if (layoutResult != null && coordinates != null) {
                markdownLinkLayouts[key] = MarkdownLinkTextLayout(text, layoutResult, coordinates)
            } else {
                markdownLinkLayouts.remove(key)
            }
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

    fun copyText(fullText: String): String {
        val selected = if (active) selectionState.selectedTexts else emptyList()
        return textAttachmentCopyText(selected, fullText)
    }

    fun reset() {
        active = false
        pendingPosition = null
    }
}

@Composable
internal fun rememberTextAttachmentSelectionController(
    candidate: TextAttachmentCandidate,
    readerState: TextAttachmentReaderState,
): TextAttachmentSelectionController {
    val selectionState = rememberSelectionState()
    val controller = remember(candidate, selectionState) { TextAttachmentSelectionController(selectionState) }
    val layoutSnapshot = controller.selectableLayoutSnapshot

    LaunchedEffect(readerState) {
        if (readerState !is TextAttachmentReaderState.Ready) controller.reset()
    }
    LaunchedEffect(controller.active, layoutSnapshot, controller.pendingPosition) {
        if (!controller.active || controller.pendingPosition == null || layoutSnapshot.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        controller.seedPendingSelection()
    }
    return controller
}

@Composable
internal fun Modifier.textAttachmentSelectionLongPress(
    preview: TextAttachmentPreview,
    onLongPress: (Offset) -> Boolean,
): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }
        .pointerInput(preview) {
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
internal fun TextAttachmentSelectableContent(
    preview: TextAttachmentPreview,
    selection: TextAttachmentSelectionController,
    mentionDisplayName: ((String) -> String?)?,
    onNostrProfileTap: ((String) -> Unit)?,
    onCopyLink: (String) -> Unit,
) {
    val content: @Composable () -> Unit = {
        preview.markdownDocument?.takeIf { it.blocks.isNotEmpty() }?.let { document ->
            MarkdownMessageBody(
                document = document,
                modifier = Modifier.fillMaxWidth(),
                mentionDisplayName = mentionDisplayName,
                onNostrProfileTap = onNostrProfileTap,
                onSelectableTextLayoutChanged = selection.selectableTextLayoutReporter,
                onLinkTextLayoutChanged = selection.markdownLinkLayoutReporter,
                onCopyLink = onCopyLink,
            )
        } ?: ReaderSelectablePlainText(
            text = preview.text,
            onSelectableTextLayoutChanged = selection.selectableTextLayoutReporter,
        )
    }
    if (selection.active) {
        SelectionContainer(state = selection.selectionState) { content() }
    } else {
        content()
    }
}

@Composable
private fun ReaderSelectablePlainText(
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
