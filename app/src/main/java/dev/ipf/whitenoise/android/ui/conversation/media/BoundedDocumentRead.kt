package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.MediaPipeline
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.util.Locale

internal sealed interface BoundedDocumentRead {
    data class Success(
        val bytes: ByteArray,
    ) : BoundedDocumentRead

    data object TooLarge : BoundedDocumentRead

    data object Empty : BoundedDocumentRead

    data object Unreadable : BoundedDocumentRead
}

/** Read a provider stream once, without trusting its reported size or buffering beyond [maxBytes]. */
internal fun readBoundedDocument(
    maxBytes: Int,
    open: () -> InputStream?,
): BoundedDocumentRead =
    try {
        val stream = open()
        if (stream == null) {
            BoundedDocumentRead.Unreadable
        } else {
            val bytes = stream.use { MediaPipeline.readBoundedBytes(it, maxBytes) }
            when {
                bytes == null -> BoundedDocumentRead.TooLarge
                bytes.isEmpty() -> BoundedDocumentRead.Empty
                else -> BoundedDocumentRead.Success(bytes)
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        BoundedDocumentRead.Unreadable
    }

private val CONCRETE_MIME = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")

/** Keep a concrete provider MIME where possible; never derive an executable type from a filename. */
internal fun normalizeDocumentMime(reported: String?): String {
    val concrete =
        reported
            .orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
    return concrete.takeIf { CONCRETE_MIME.matches(it) } ?: "application/octet-stream"
}

internal fun safeDocumentDisplayName(name: String?): String = MediaPipeline.safeDisplayName(name.orEmpty(), fallback = "file")
