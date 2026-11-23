@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaTransport
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** The first page of [bytes], staged from [source], or null when it cannot be rendered. */
    suspend fun firstPage(
        source: Uri,
        bytes: ByteArray,
    ): ComposerPdfFirstPage? =
        runCatchingCancellable {
            started = true
            val domain =
                MediaTrustDomain.DocumentSession(COMPOSER_PDF_ACCOUNT, source.toString(), UUID.randomUUID().toString())
            var result: ComposerPdfFirstPage? = null
            try {
                withPrivatePdfFile(context.cacheDir, { AttachmentPlaintext.Bytes(bytes) }) { file ->
                    val page = withContext(Dispatchers.IO) { transport.pdf(file, domain, 0, FIRST_PAGE_EDGE, true) }
                    result = page?.let { ComposerPdfFirstPage(it.pages, it.bitmap?.asImageBitmap()) }
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
 * Reads what every prepared document on the shelf says about PDF metadata into [choices], whether or not its card is
 * on screen, so a send can ask about all of them at once. Content decides, so a PDF with another name is read too.
 */
@Composable
internal fun ReadComposerPdfMetadata(
    choices: ComposerPdfMetadataChoices,
    documentUris: List<Uri>,
    preparedDocuments: Map<Uri, DraftPreparedPhoto>,
) {
    val read = remember(choices) { mutableMapOf<Uri, DraftPreparedPhoto>() }
    LaunchedEffect(choices, documentUris, preparedDocuments) {
        documentUris.distinct().forEach { uri ->
            val document = preparedDocuments[uri]
            if (document == null) choices.stopReading(uri)
            if (document == null || read[uri] === document) return@forEach
            choices.reading(uri)
            val attachment = document.attachment
            val metadata = withContext(Dispatchers.Default) { PdfMetadataScrubber.inspect(attachment.plaintext) }
            read[uri] = document
            choices.record(uri, ComposerPdfState(safeDocumentDisplayName(attachment.fileName), metadata))
        }
    }
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
    val firstPage by produceState<ComposerPdfFirstPage?>(null, uri, document) {
        value = document?.attachment?.plaintext?.let { renderer.firstPage(uri, it) }
    }
    val state = choices.state(uri)
    ComposerPdfAttachmentCard(
        label = label,
        pages = firstPage?.pages,
        firstPage = firstPage?.image,
        privacy = composerPdfPrivacy(state?.metadata, choices.keepsOriginal(uri)),
        onPreview = onPreview,
        onRemove = onRemove,
        modifier = modifier,
        enabled = enabled,
    )
}
