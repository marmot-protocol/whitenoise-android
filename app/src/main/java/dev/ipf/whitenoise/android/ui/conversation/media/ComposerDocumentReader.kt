@file:Suppress("MatchingDeclarationName") // The staged reader host keeps its small selection value beside it.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

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
    val target = attachmentReaderTarget(attachment.mediaType, attachment.fileName) ?: return
    val context = LocalContext.current
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
                withPrivatePdfFile(context.cacheDir, AttachmentPlaintext.Bytes(attachment.plaintext)) { file ->
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
