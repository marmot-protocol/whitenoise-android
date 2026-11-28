package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult

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
    internal val sendUris: List<Uri>,
    internal val send: (Set<Uri>) -> Unit,
    internal val cancel: () -> Unit,
)

/** A send waiting until every staged PDF has been read. */
private class ComposerPdfPendingSend(
    val uris: List<Uri>,
    val onCancel: () -> Unit,
    val send: (Set<Uri>) -> Unit,
)

/**
 * The composer's staged PDFs whose metadata cannot be removed, and the person's choice to send them as they are
 * (#3377). The shelf reads each prepared PDF whether or not its card is on screen. Only the explicit per-file
 * toggle remains on the shelf. A confirmation for an uncleanable PDF is handed directly to one send.
 */
@Stable
internal class ComposerPdfMetadataChoices {
    private val states = mutableStateMapOf<Uri, ComposerPdfState>()
    private val reading = mutableStateMapOf<Uri, Unit>()
    private var keepMetadata by mutableStateOf(emptySet<Uri>())
    private var pendingSend: ComposerPdfPendingSend? = null

    /** The send waiting for a choice, shown as a dialog until it is confirmed or cancelled. */
    var decision by mutableStateOf<ComposerPdfDecision?>(null)
        private set

    fun state(uri: Uri): ComposerPdfState? = states[uri]

    /** Marks [uri] as being read, so a send waits for its result. */
    fun reading(uri: Uri) {
        keepMetadata -= uri
        reading[uri] = Unit
        if (decision?.sendUris?.contains(uri) == true) cancel()
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
        if (waiting != null && waiting.uris.all { it in states && it !in reading }) {
            pendingSend = null
            confirmThen(waiting.uris, waiting.onCancel, waiting.send)
        }
    }

    fun keepsOriginal(uri: Uri): Boolean = uri in keepMetadata

    fun toggleKeepMetadata(uri: Uri) {
        if (states[uri]?.metadata !is PdfMetadataResult.Removable || uri in reading) return
        keepMetadata = if (uri in keepMetadata) keepMetadata - uri else keepMetadata + uri
    }

    /** Forgets everything about PDFs that left the shelf. */
    fun retainOnly(uris: Collection<Uri>) {
        val staged = uris.toSet()
        states.keys.filter { it !in staged }.forEach(states::remove)
        reading.keys.filter { it !in staged }.forEach(reading::remove)
        if (!staged.containsAll(keepMetadata)) keepMetadata = keepMetadata intersect staged
        if (pendingSend?.uris?.any { it !in staged } == true || decision?.sendUris?.any { it !in staged } == true) {
            cancel()
        } else {
            resumeWaitingSend()
        }
    }

    /**
     * Runs [send] with the exact PDFs allowed to keep metadata. Refused PDFs require a fresh answer each time;
     * a send waits for all its documents to finish inspection before deciding.
     */
    fun confirmThen(
        uris: List<Uri>,
        onCancel: () -> Unit,
        send: (Set<Uri>) -> Unit,
    ) {
        val selected = keepMetadata intersect uris.toSet()
        val refused = uris.distinct().filter { states[it]?.metadata is PdfMetadataResult.Refused }
        when {
            decision != null || pendingSend != null -> onCancel()
            uris.any { it !in states || it in reading } -> pendingSend = ComposerPdfPendingSend(uris, onCancel, send)
            refused.isEmpty() -> send(selected)
            else ->
                decision =
                    decisionFor(refused.mapNotNull(states::get), refused, uris, onCancel) { approved ->
                        send(selected + approved)
                    }
        }
    }

    /** Delivers consent only to the waiting send, even if dispatch then refuses it. */
    fun confirm() {
        val waiting = decision ?: return
        decision = null
        waiting.send(waiting.uris.toSet())
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

/** The question for [undecided] PDFs, whose read states are [pending]. */
private fun decisionFor(
    pending: List<ComposerPdfState>,
    undecided: List<Uri>,
    sendUris: List<Uri>,
    onCancel: () -> Unit,
    send: (Set<Uri>) -> Unit,
) = ComposerPdfDecision(
    names = pending.map { it.name },
    reasons = pending.mapNotNullTo(linkedSetOf()) { (it.metadata as? PdfMetadataResult.Refused)?.reason },
    uris = undecided,
    sendUris = sendUris,
    send = send,
    cancel = onCancel,
)
