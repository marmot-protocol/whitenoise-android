package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.state.PendingAttachment

/** What the composer learned about one staged PDF: its [name] and what its prepared bytes say about metadata. */
internal data class ComposerPdfState(
    val name: String,
    val metadata: PdfMetadataResult,
)

/** A send waiting for the person to choose whether PDFs that keep their metadata go as they are. */
internal class ComposerPdfDecision(
    val names: List<String>,
    val reasons: Set<PdfMetadataRefusal>,
    internal val uris: List<Uri>,
    internal val send: () -> Unit,
    internal val cancel: () -> Unit,
)

/** A send waiting until every staged PDF has been read. */
private class ComposerPdfPendingSend(
    val uris: List<Uri>,
    val onCancel: () -> Unit,
    val send: () -> Unit,
)

/**
 * The composer's staged PDFs whose metadata cannot be removed, and the person's choice to send them as they are
 * (#3377). The shelf reads every prepared PDF into [record], whether or not its card is on screen. A choice covers
 * one send: [marked] attaches it to that send's prepared attachments as [PendingAttachment.keepsPdfMetadata], which
 * carries it through a collection's later chunks, and forgets it. It is never saved with a draft.
 */
@Stable
internal class ComposerPdfMetadataChoices {
    private val states = mutableStateMapOf<Uri, ComposerPdfState>()
    private val reading = mutableStateMapOf<Uri, Unit>()
    private var keepOriginal by mutableStateOf(emptySet<Uri>())
    private var pendingSend: ComposerPdfPendingSend? = null

    /** The send waiting for a choice, shown as a dialog until it is confirmed or cancelled. */
    var decision by mutableStateOf<ComposerPdfDecision?>(null)
        private set

    fun state(uri: Uri): ComposerPdfState? = states[uri]

    /** Marks [uri] as being read, so a send waits for its result. */
    fun reading(uri: Uri) {
        reading[uri] = Unit
    }

    fun record(
        uri: Uri,
        state: ComposerPdfState,
    ) {
        states[uri] = state
        stopReading(uri)
    }

    /** Marks [uri] as no longer being read, for a document that is not prepared yet, and resumes a waiting send. */
    fun stopReading(uri: Uri) {
        reading -= uri
        resumeWaitingSend()
    }

    private fun resumeWaitingSend() {
        val waiting = pendingSend
        if (waiting != null && waiting.uris.none { it in reading }) {
            pendingSend = null
            confirmThen(waiting.uris, waiting.onCancel, waiting.send)
        }
    }

    fun keepsOriginal(uri: Uri): Boolean = uri in keepOriginal

    /** Forgets everything about PDFs that left the shelf. */
    fun retainOnly(uris: Collection<Uri>) {
        val staged = uris.toSet()
        states.keys.filter { it !in staged }.forEach(states::remove)
        reading.keys.filter { it !in staged }.forEach(reading::remove)
        if (!staged.containsAll(keepOriginal)) keepOriginal = keepOriginal intersect staged
        resumeWaitingSend()
    }

    /**
     * [prepared] with the person's choice to send a PDF as it is attached to that PDF's own attachment. The choice is
     * used up: a later send of the same file asks again.
     */
    fun marked(prepared: Map<Uri, PendingAttachment>): Map<Uri, PendingAttachment> {
        val chosen = keepOriginal
        keepOriginal = emptySet()
        return prepared.mapValues { (uri, attachment) ->
            if (uri in chosen) attachment.copy(keepsPdfMetadata = true) else attachment
        }
    }

    /**
     * Runs [send] at once unless some of [uris] are PDFs that keep their metadata and have no choice yet. Then it
     * asks first: confirming records the choice for those PDFs and sends, cancelling runs [onCancel]. A send that
     * names a PDF still being read waits for it.
     */
    fun confirmThen(
        uris: List<Uri>,
        onCancel: () -> Unit,
        send: () -> Unit,
    ) {
        if (decision != null || pendingSend != null) return onCancel()
        if (uris.any { it in reading }) {
            pendingSend = ComposerPdfPendingSend(uris, onCancel, send)
            return
        }
        val undecided =
            uris.distinct().filter { it !in keepOriginal && states[it]?.metadata is PdfMetadataResult.Refused }
        if (undecided.isEmpty()) return send()
        val pending = undecided.mapNotNull(states::get)
        decision =
            ComposerPdfDecision(
                names = pending.map { it.name },
                reasons = pending.mapNotNullTo(linkedSetOf()) { (it.metadata as? PdfMetadataResult.Refused)?.reason },
                uris = undecided,
                send = send,
                cancel = onCancel,
            )
    }

    /** Sends the waiting PDFs as they are. */
    fun confirm() {
        val waiting = decision ?: return
        decision = null
        keepOriginal = keepOriginal + waiting.uris
        waiting.send()
    }

    /** Keeps the waiting send on the shelf, including one still waiting for a PDF to be read. */
    fun cancel() {
        val waiting = decision
        val queued = pendingSend
        decision = null
        pendingSend = null
        waiting?.cancel?.invoke()
        queued?.onCancel?.invoke()
    }
}
