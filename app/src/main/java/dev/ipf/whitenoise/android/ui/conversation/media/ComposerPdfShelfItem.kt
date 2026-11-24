@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaTrustDomain
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaTransport
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.conversation.MEDIA_ALBUM_MAX_TOTAL_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
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
 * The composer's PDF bookkeeping beside the shelf: forgets PDFs that left it, reads every staged one, asks before a
 * send that keeps metadata, and cancels a send still waiting when the shelf leaves, which keeps its attachments.
 */
@Composable
internal fun ComposerPdfShelfEffects(
    choices: ComposerPdfMetadataChoices,
    documentUris: List<Uri>,
    preparedDocuments: Map<Uri, DraftPreparedPhoto>,
    metadata: Map<Uri, LocalPreviewMetadata>,
) {
    LaunchedEffect(choices, documentUris) { choices.retainOnly(documentUris) }
    ReadComposerPdfMetadata(choices, documentUris, preparedDocuments) { uri ->
        preparedDocuments[uri]?.attachment?.fileName ?: metadata[uri]?.displayName ?: uri.lastPathSegment
    }
    ComposerPdfMetadataDialog(choices)
    DisposableEffect(choices) { onDispose { choices.cancel() } }
}

/**
 * Reads what every document on the shelf says about PDF metadata into [choices], whether or not its card is on
 * screen, so a send can ask about all of them at once. Content decides, so a PDF with another name is read too. A
 * prepared document is read from its prepared bytes. One too large to prepare is read where it is, as the send will
 * read it; one still being prepared waits for its bytes. Every document still to read is marked first, so a send
 * waits for all of them, not only the one being read.
 */
@Composable
private fun ReadComposerPdfMetadata(
    choices: ComposerPdfMetadataChoices,
    documentUris: List<Uri>,
    preparedDocuments: Map<Uri, DraftPreparedPhoto>,
    names: (Uri) -> String?,
) {
    val resolver = LocalContext.current.contentResolver
    val currentNames by rememberUpdatedState(names)
    // What each staged URI was last read as: its prepared draft's digest, or the URI itself when unprepared.
    val read = remember(choices) { mutableMapOf<Uri, String>() }
    LaunchedEffect(choices, documentUris, preparedDocuments) {
        read.keys.retainAll(documentUris.toSet())
        val pending =
            documentUris.distinct().mapNotNull { uri ->
                val key = preparedDocuments[uri]?.attachmentDigest ?: uri.toString()
                (uri to key).takeIf { read[uri] != key }
            }
        pending.forEach { (uri, _) -> choices.reading(uri) }
        pending.forEach { (uri, key) ->
            val bytes = preparedDocuments[uri]?.attachment?.plaintext
            val name = currentNames(uri)
            val metadata =
                withContext(Dispatchers.IO) {
                    if (bytes != null) PdfMetadataScrubber.inspect(bytes) else unpreparedPdf(resolver, uri, name)
                }
            if (metadata == null) {
                choices.stopReading(uri)
            } else {
                read[uri] = key
                choices.record(uri, ComposerPdfState(safeDocumentDisplayName(name), metadata))
            }
        }
    }
}

/**
 * What a document too large to prepare says about PDF metadata, read through the provider's file, or null for one
 * that will be prepared. A PDF by name or type that the provider cannot open as a file reads as unsupported, so a
 * send asks about it.
 */
private fun unpreparedPdf(
    resolver: ContentResolver,
    uri: Uri,
    name: String?,
): PdfMetadataResult? {
    if (queryContentSize(resolver, uri) <= MEDIA_ALBUM_MAX_TOTAL_BYTES) return null
    val named = documentAttachmentKind(safeGetType(resolver, uri), name.orEmpty()) == DocumentAttachmentKind.Pdf
    val unreadable = if (named) PdfMetadataResult.Refused(PdfMetadataRefusal.Unsupported) else null
    return runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            FileInputStream(asset.fileDescriptor).use { input ->
                val channel = input.channel
                val length = if (asset.length >= 0) asset.length else channel.size() - asset.startOffset
                // A pipe reports no size; it cannot be mapped, so it is read as a PDF that could not be opened.
                if (length > 0) PdfMetadataScrubber.inspect(channel, asset.startOffset, length) else null
            }
        }
    }.getOrNull() ?: unreadable
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
