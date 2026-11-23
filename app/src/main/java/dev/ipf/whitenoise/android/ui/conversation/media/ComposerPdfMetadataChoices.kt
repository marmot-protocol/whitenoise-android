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

/**
 * The composer's staged PDFs whose metadata cannot be removed, and the person's choice to send them as they are
 * (#3377). The choice belongs to the PDFs on the shelf: removing one, or sending it, forgets it, and it is never
 * saved with a draft. Prepared PDFs carry it into the send as [PendingAttachment.keepsPdfMetadata], so it holds for
 * a collection read in later chunks.
 */
@Stable
internal class ComposerPdfMetadataChoices {
    private val states = mutableStateMapOf<Uri, ComposerPdfState>()
    private var keepOriginal by mutableStateOf(emptySet<Uri>())

    /** The send waiting for a choice, shown as a dialog until it is confirmed or cancelled. */
    var decision by mutableStateOf<ComposerPdfDecision?>(null)
        private set

    fun state(uri: Uri): ComposerPdfState? = states[uri]

    fun record(
        uri: Uri,
        state: ComposerPdfState,
    ) {
        states[uri] = state
    }

    fun keepsOriginal(uri: Uri): Boolean = uri in keepOriginal

    /** Forgets everything about PDFs that left the shelf. */
    fun retainOnly(uris: Collection<Uri>) {
        val staged = uris.toSet()
        states.keys.filter { it !in staged }.forEach(states::remove)
        if (!staged.containsAll(keepOriginal)) keepOriginal = keepOriginal intersect staged
    }

    /** [prepared] with the person's choice to send a PDF as it is attached to that PDF's own attachment. */
    fun marked(prepared: Map<Uri, PendingAttachment>): Map<Uri, PendingAttachment> =
        prepared.mapValues { (uri, attachment) ->
            if (uri in keepOriginal) attachment.copy(keepsPdfMetadata = true) else attachment
        }

    /**
     * Runs [send] at once unless some of [uris] are PDFs that keep their metadata and have no choice yet. Then it
     * asks first: confirming records the choice for those PDFs and sends, cancelling runs [onCancel].
     */
    fun confirmThen(
        uris: List<Uri>,
        onCancel: () -> Unit,
        send: () -> Unit,
    ) {
        val undecided =
            uris.distinct().filter { it !in keepOriginal && states[it]?.metadata is PdfMetadataResult.Refused }
        if (undecided.isEmpty() || decision != null) {
            if (decision == null) send() else onCancel()
            return
        }
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

    /** Keeps the waiting send on the shelf. */
    fun cancel() {
        val waiting = decision ?: return
        decision = null
        waiting.cancel()
    }
}
