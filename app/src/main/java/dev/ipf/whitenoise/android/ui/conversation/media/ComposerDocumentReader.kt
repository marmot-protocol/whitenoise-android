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
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

// Names this process's preview directory. Readers live only in this process, so every other
// process's directory is an orphan left by a kill before the reader's cleanup ran.
private val processPreviewName = "process-" + UUID.randomUUID()

/** This process's private directory for staged PDF previews, under [cacheDir]. */
internal fun composerPreviewDirectory(cacheDir: File): File = File(previewRoot(cacheDir), processPreviewName)

private fun previewRoot(cacheDir: File) = File(cacheDir, MediaCacheDirs.COMPOSER_DOCUMENT_READER)

/** Deletes previews left by earlier processes at any age; safe while this process has a reader open. */
internal fun sweepOrphanedComposerPreviews(cacheDir: File) {
    previewRoot(cacheDir)
        .listFiles()
        ?.filterNot { it.name == processPreviewName }
        ?.forEach { orphan -> runCatching { orphan.deleteRecursively() } }
}

/** The coroutine owns even partial writes, including cancellation at the dispatcher return boundary. */
internal suspend fun withComposerPdfFile(
    cacheDir: File,
    bytes: ByteArray,
    use: suspend (File) -> Unit,
) = withContext(Dispatchers.IO) {
    require(bytes.size.toLong() in 1..PdfPagePolicy.MAX_INPUT)
    val directory = composerPreviewDirectory(cacheDir)
    check(directory.isDirectory || directory.mkdirs())
    val file = File.createTempFile("preview-", ".pdf", directory)
    try {
        ensureActive()
        file.outputStream().use { it.write(bytes) }
        ensureActive()
        use(file)
    } finally {
        file.delete()
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
            loadFile = { null },
            withFile = { use ->
                check(actions.isCurrent())
                withComposerPdfFile(context.cacheDir, attachment.plaintext) { file ->
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
