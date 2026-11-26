@file:Suppress("MatchingDeclarationName") // The staged reader host keeps its small selection value beside it.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.draftPresentationFile
import dev.ipf.whitenoise.android.ui.conversation.draftPresentationUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** URI locates a staged occurrence; referential artifact identity prevents same-URI replacements reviving it. */
internal class ComposerDocumentSelection(
    val uri: Uri,
    val prepared: DraftPreparedPhoto,
) {
    fun isCurrent(
        uris: List<Uri>,
        documents: Map<Uri, DraftPreparedPhoto>,
        sending: Boolean,
    ): Boolean = !sending && uri in uris && documents[uri] === prepared
}

/** The shelf can open inline reader types and a materialized draft PDF within the renderer's file limit. */
internal fun composerDocumentReaderTarget(
    context: Context,
    uri: Uri,
    prepared: DraftPreparedPhoto?,
): AttachmentReaderTarget? {
    val document = prepared ?: return null
    val attachment = document.attachment
    return attachmentReaderTarget(attachment.mediaType, attachment.fileName)?.takeIf { target ->
        attachment.hasInlineBody() ||
            (
                target.documentKind == DocumentAttachmentKind.Pdf &&
                    document.storedBytes in 1..PdfPagePolicy.MAX_INPUT &&
                    draftPresentationFile(context, attachment, createDirectory = false)
                        ?.let { draftPresentationUri(context, it, attachment) } == uri
            )
    }
}

/** Lends the exact restored presentation file to the renderer without buffering or making another disk copy. */
internal suspend fun withComposerPdfFile(
    context: Context,
    document: DraftPreparedPhoto,
    use: suspend (File) -> Unit,
) {
    val attachment = document.attachment
    if (attachment.hasInlineBody()) {
        withPrivatePdfFile(context.cacheDir, { AttachmentPlaintext.Bytes(attachment.plaintext) }, use)
    } else {
        withContext(Dispatchers.IO) {
            require(document.storedBytes in 1..PdfPagePolicy.MAX_INPUT)
            val file = checkNotNull(draftPresentationFile(context, attachment, createDirectory = false))
            check(file.isFile && file.length() == document.storedBytes)
            use(file)
        }
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun ComposerDocumentReader(
    selection: ComposerDocumentSelection,
    appState: WhiteNoiseAppState,
    account: String,
    sourceIsCurrent: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val current = rememberUpdatedState(sourceIsCurrent)
    val actions =
        remember(selection) {
            TextAttachmentNativeActions(sourceIsCurrent = { current.value() }, shareSource = null, saveSource = null)
        }
    DisposableEffect(actions) { onDispose { actions.release() } }
    // Composition observes every state the owner check reads, so removal or a send claim closes the reader.
    if (!actions.isCurrent()) {
        LaunchedEffect(actions) {
            actions.release()
            onDismiss()
        }
        return
    }
    val attachment = selection.prepared.attachment
    val context = LocalContext.current
    val target = composerDocumentReaderTarget(context, selection.uri, selection.prepared) ?: return
    val loadBytes: suspend () -> ByteArray = {
        check(actions.isCurrent())
        val bytes = readForTextReader(AttachmentPlaintext.Bytes(attachment.plaintext))
        check(actions.isCurrent())
        bytes
    }
    if (target.documentKind != null) {
        DocumentAttachmentViewer(
            kind = target.documentKind,
            name = attachment.fileName,
            account = account,
            source = selection.uri.toString(),
            actions = actions,
            loadBytes = loadBytes,
            withPdfFile = { use ->
                check(actions.isCurrent())
                withComposerPdfFile(context, selection.prepared) { file ->
                    check(actions.isCurrent())
                    use(file)
                }
            },
            onOpenExternal = null,
            onDismiss = onDismiss,
        )
    } else {
        TextAttachmentReaderDialog(
            actions = actions,
            candidate = checkNotNull(target.textCandidate),
            appState = appState,
            speechContext = null,
            loadBytes = loadBytes,
            onOpenExternal = null,
            onDismiss = onDismiss,
        )
    }
}
