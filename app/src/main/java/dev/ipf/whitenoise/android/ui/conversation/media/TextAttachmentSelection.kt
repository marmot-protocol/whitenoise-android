@file:Suppress(
    "FunctionNaming", // Compose UI functions intentionally use PascalCase.
    "MatchingDeclarationName", // The controller and its Compose adapters form one selection unit.
)

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderSelectablePlainText
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderTextSelectionController
import dev.ipf.whitenoise.android.ui.conversation.messages.readerTextSelectionLongPress
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberReaderTextSelectionController

internal typealias TextAttachmentSelectionController = ReaderTextSelectionController

@Composable
internal fun rememberTextAttachmentSelectionController(
    candidate: TextAttachmentCandidate,
    readerState: TextAttachmentReaderState,
): TextAttachmentSelectionController {
    val controller = rememberReaderTextSelectionController(candidate)

    LaunchedEffect(readerState) {
        if (readerState !is TextAttachmentReaderState.Ready) controller.reset()
    }
    return controller
}

@Composable
internal fun Modifier.textAttachmentSelectionLongPress(
    preview: TextAttachmentPreview,
    onLongPress: (Offset) -> Boolean,
): Modifier = readerTextSelectionLongPress(preview, onLongPress)

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
