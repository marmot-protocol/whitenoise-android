package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.ui.parseMarkdownOrEmptyDocument
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal fun messageBubbleHasEditedMarkdown(
    editState: EditState?,
    record: AppMessageRecordFfi,
): Boolean = editState != null && record.kind == CHAT_MESSAGE_KIND

internal fun messageBubbleEditedMarkdownSourceText(
    editState: EditState?,
    record: AppMessageRecordFfi,
    deleted: Boolean,
    persistedFailure: Boolean,
): String? {
    if (deleted || persistedFailure || !messageBubbleHasEditedMarkdown(editState, record)) return null
    return editState!!.latestText.takeIf { it.isNotBlank() }
}

internal fun messageBubbleEditedDisplayMarkdownDocument(
    parsedDocument: MarkdownDocumentFfi?,
    editState: EditState?,
    record: AppMessageRecordFfi,
): MarkdownDocumentFfi? = parsedDocument?.takeIf { messageBubbleHasEditedMarkdown(editState, record) }

@Composable
internal fun rememberMessageBubbleEditedDisplayMarkdownDocument(
    record: AppMessageRecordFfi,
    editState: EditState?,
    deleted: Boolean,
    persistedFailure: Boolean,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
): MarkdownDocumentFfi? {
    val sourceText =
        remember(editState, record.messageIdHex, record.kind, deleted, persistedFailure) {
            messageBubbleEditedMarkdownSourceText(editState, record, deleted, persistedFailure)
        }
    var parsedDocument by remember(record.messageIdHex, sourceText) {
        mutableStateOf<MarkdownDocumentFfi?>(null)
    }
    LaunchedEffect(record.messageIdHex, sourceText) {
        if (sourceText == null) {
            parsedDocument = null
            return@LaunchedEffect
        }
        val document = parseMarkdownOrEmptyDocument(sourceText, parseMarkdown)
        coroutineContext.ensureActive()
        parsedDocument = document
    }
    return messageBubbleEditedDisplayMarkdownDocument(parsedDocument, editState, record)
}

private const val CHAT_MESSAGE_KIND = 9uL
