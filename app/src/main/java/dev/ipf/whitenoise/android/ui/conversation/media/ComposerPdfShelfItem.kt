@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import dev.ipf.whitenoise.android.media.pdf.ArrayPdfBytes
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.media.pdf.looksLikePdf
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaTransport
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

// The thumbnail is at most 56dp tall; this edge keeps it sharp at high densities without a large bitmap.
private const val FIRST_PAGE_EDGE = 256

/** A rendered first page and the document's page count. */
internal class ComposerPdfFirstPage(
    val pages: Int,
    val image: ImageBitmap?,
)

/**
 * Renders composer PDFs' first pages in the isolated document parser, one fresh worker per PDF, as the in-app
 * reader does. The worker process starts on first use and stops when the shelf leaves.
 */
internal class ComposerPdfPageRenderer(
    private val context: Context,
) : AutoCloseable {
    private val transport by lazy {
        IsolatedMediaTransport(context, "composer_documents", spares = 0, liveLimit = 1)
    }
    private var started = false

    /** The first page of the prepared or provider-backed PDF, within the isolated reader's input limit. */
    suspend fun firstPage(
        source: Uri,
        document: DraftPreparedPhoto?,
    ): ComposerPdfFirstPage? =
        runCatchingCancellable {
            started = true
            val domain =
                MediaTrustDomain.DocumentSession(COMPOSER_PDF_ACCOUNT, source.toString(), UUID.randomUUID().toString())
            var result: ComposerPdfFirstPage? = null
            try {
                val render: suspend (File) -> Unit = { file ->
                    val page = withContext(Dispatchers.IO) { transport.pdf(file, domain, 0, FIRST_PAGE_EDGE, true) }
                    result = page?.let { ComposerPdfFirstPage(it.pages, it.bitmap?.asImageBitmap()) }
                }
                if (document != null) {
                    withComposerPdfFile(context, document, render)
                } else {
                    withProviderPdfSnapshot(context, source) { file -> render(file) }
                }
            } finally {
                transport.retireDomain(domain)
            }
            result
        }.getOrNull()

    override fun close() {
        if (started) transport.close()
    }

    private companion object {
        // Composer previews belong to no account's attachment cache; each opening is its own trust domain.
        const val COMPOSER_PDF_ACCOUNT = "composer"
    }
}

@Composable
internal fun rememberComposerPdfPageRenderer(): ComposerPdfPageRenderer {
    val context = LocalContext.current.applicationContext
    val renderer = remember(context) { ComposerPdfPageRenderer(context) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    return renderer
}

/**
 * The composer's PDF bookkeeping beside the shelf: forgets PDFs that left it, reads every staged one, asks before a
 * send that keeps metadata, and cancels a send still waiting when the shelf leaves, which keeps its attachments.
 */
@Composable
internal fun ComposerPdfShelfEffects(
    choices: ComposerPdfMetadataChoices,
    documentUris: List<Uri>,
    preparedDocuments: Map<Uri, DraftPreparedPhoto>,
    metadata: Map<Uri, LocalPreviewMetadata>,
    preparing: Boolean,
) {
    LaunchedEffect(choices, documentUris) { choices.retainOnly(documentUris) }
    ReadComposerPdfMetadata(choices, documentUris, preparedDocuments, preparing) { uri ->
        preparedDocuments[uri]?.attachment?.fileName ?: metadata[uri]?.displayName ?: uri.lastPathSegment
    }
    ComposerPdfMetadataDialog(choices)
    DisposableEffect(choices) { onDispose { choices.cancel() } }
}

/** Marks every new document as pending before starting background reads, including drafts still preparing. */
@Composable
private fun ReadComposerPdfMetadata(
    choices: ComposerPdfMetadataChoices,
    documentUris: List<Uri>,
    preparedDocuments: Map<Uri, DraftPreparedPhoto>,
    preparing: Boolean,
    names: (Uri) -> String?,
) {
    val context = LocalContext.current
    val currentNames by rememberUpdatedState(names)
    val read = remember(choices) { mutableMapOf<Uri, String>() }
    LaunchedEffect(choices, documentUris, preparedDocuments, preparing) {
        read.keys.retainAll(documentUris.toSet())
        val pending =
            documentUris.distinct().mapNotNull { uri ->
                val key = preparedDocuments[uri]?.attachmentDigest ?: uri.toString()
                (uri to key).takeIf { read[uri] != key }
            }
        pending.forEach { (uri, _) -> choices.reading(uri) }
        pending.forEach { (uri, key) ->
            val document = preparedDocuments[uri]
            if (document == null && preparing) return@forEach
            val name = currentNames(uri)
            val metadata = inspectComposerPdf(context, uri, name, document)
            read[uri] = key
            choices.record(uri, ComposerPdfState(safeDocumentDisplayName(name), metadata))
        }
    }
}

/** Works on app-owned bytes or a bounded private copy, even when a provider only exposes a stream. */
internal suspend fun inspectComposerPdf(
    context: Context,
    uri: Uri,
    name: String?,
    document: DraftPreparedPhoto? = null,
): PdfMetadataResult =
    withContext(Dispatchers.IO) {
        val attachment = document?.attachment
        if (attachment?.hasInlineBody() == true) {
            return@withContext PdfMetadataScrubber.inspect(attachment.plaintext)
        }
        val named = documentAttachmentKind(safeGetType(context.contentResolver, uri), name.orEmpty()) == DocumentAttachmentKind.Pdf
        runCatchingCancellable {
            withProviderPdfSnapshot(context, uri) { PdfMetadataScrubber.inspect(it) }
        }.getOrElse {
            if (named || it is PdfSnapshotUnavailable) {
                PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported)
            } else {
                PdfMetadataResult.NotPdf
            }
        } ?: PdfMetadataResult.NotPdf
    }

private class PdfSnapshotUnavailable : java.io.IOException()

/** Sniffs the exact stream copied, and removes the temporary input after inspection or rendering. */
private suspend fun <T> withProviderPdfSnapshot(
    context: Context,
    uri: Uri,
    use: suspend (File) -> T,
): T? =
    withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)?.buffered() ?: throw java.io.IOException()
        input.use {
            it.mark(PDF_SNIFF_BYTES)
            val header = it.readPdfHeader()
            if (!looksLikePdf(ArrayPdfBytes(header))) return@withContext null
            it.reset()
            val copy = readStagedDocument(documentReaderDirectory(context.cacheDir), PdfPagePolicy.MAX_INPUT, { ensureActive() }) { it }
            val source = (copy as? StagedDocumentRead.Success)?.source ?: throw PdfSnapshotUnavailable()
            source.use { use(it.file) }
        }
    }

private const val PDF_SNIFF_BYTES = 1024

private fun InputStream.readPdfHeader(): ByteArray {
    val header = ByteArray(PDF_SNIFF_BYTES)
    var count = 0
    while (count < header.size) {
        val next = read()
        if (next < 0) break
        header[count++] = next.toByte()
    }
    return header.copyOf(count)
}

/**
 * One staged PDF on the shelf: its first page and what [choices] read about it. A PDF still being prepared shows its
 * name only.
 */
@Composable
@Suppress("LongParameterList")
internal fun ComposerPdfShelfItem(
    uri: Uri,
    label: String,
    document: DraftPreparedPhoto?,
    choices: ComposerPdfMetadataChoices,
    renderer: ComposerPdfPageRenderer,
    onPreview: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val state = choices.state(uri)
    var metadataOptions by remember(uri, document) { mutableStateOf(false) }
    val offersChoice = state?.metadata is PdfMetadataResult.Removable
    if (metadataOptions && offersChoice) {
        ComposerPdfMetadataOptions(
            label = label,
            keepsMetadata = choices.keepsOriginal(uri),
            onChange = {
                choices.toggleKeepMetadata(uri)
                metadataOptions = false
            },
            onDismiss = { metadataOptions = false },
        )
    }
    val firstPage by produceState<ComposerPdfFirstPage?>(null, uri, document, state) {
        value = if (state != null && state.metadata != PdfMetadataResult.NotPdf) renderer.firstPage(uri, document) else null
    }
    ComposerPdfAttachmentCard(
        label = label,
        pages = firstPage?.pages,
        firstPage = firstPage?.image,
        privacy = composerPdfPrivacy(state?.metadata, choices.keepsOriginal(uri)),
        onPreview = onPreview,
        onRemove = onRemove,
        modifier = modifier,
        enabled = enabled,
        onMetadataOptions = if (offersChoice) ({ metadataOptions = true }) else null,
    )
}
