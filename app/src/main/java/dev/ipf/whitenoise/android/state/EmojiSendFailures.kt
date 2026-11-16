package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import java.util.Locale

/** The emoji send would exceed MDK's tag limits, so nothing was uploaded or sent. */
internal class EmojiSendLimitException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The artwork lookup or upload stage of a custom-emoji send failed, so nothing was published. Wrapping
 * the cause keeps a connection reset there from being read as an uncertain delivery, since it is a
 * definite failure the user can retry.
 */
internal class EmojiUploadFailure(
    cause: Throwable,
) : Exception("emoji upload failed before publication", cause)

/**
 * MDK refused the uploaded references, usually because the chat moved to a new epoch between upload
 * and send. Nothing was published, and a new send re-encrypts the images.
 */
internal class EmojiChatChangedException(
    cause: Throwable,
) : Exception("the chat changed while sending", cause)

/**
 * The artwork one composer send carries, looked up on its first attempt and reused by its retries.
 * [value] stays null until a lookup succeeds, so a failed lookup is retried like an upload stage.
 */
internal class SendEmojiArtwork(
    private val lookup: suspend () -> List<LocalEmojiArtwork>,
) {
    var value: List<LocalEmojiArtwork>? = null
        private set

    /** The resolved artwork, running [lookup] only until it first succeeds. */
    suspend fun resolve(): List<LocalEmojiArtwork> = value ?: lookup().also { value = it }
}

/**
 * A received emoji original could not be read because the connection was lost. Nothing was published, so the send
 * retries its lookup instead of dropping the artwork, whatever the underlying error's text says.
 */
internal class EmojiArtworkConnectionLost(
    cause: Throwable,
) : Exception("emoji artwork read lost its connection", cause)

/** A received emoji original whose bytes differ from the digest its message named; it is never sent. */
internal class ReceivedEmojiMismatchException : IllegalStateException("emoji plaintext hash mismatch")

/** Whether [throwable] is an upload-stage failure that a connectivity recovery can retry without duplicating. */
internal fun isRetryableEmojiUploadFailure(throwable: Throwable): Boolean {
    val chain = generateSequence(throwable) { it.cause }
    return chain.any { it is EmojiUploadFailure } &&
        (chain.any { it is EmojiArtworkConnectionLost } || isConnectivityFailure(throwable))
}

/** Whether [throwable] or one of its causes reads as lost connectivity, which a later attempt may clear. */
internal fun isConnectivityFailure(throwable: Throwable): Boolean {
    val chain = generateSequence(throwable) { it.cause }.toList()
    val text = chain.joinToString("\n") { "${it.javaClass.simpleName} ${it.message}" }.lowercase(Locale.ROOT)
    return chain.any { it is MarmotKitException.TransportClosed } ||
        listOf("connection", "timed out", "timeout", "no relay", "network", "unreachable").any(text::contains)
}

/**
 * Whether [throwable] says MDK rejected a media reference, for any media kind, because of its epoch, either stale
 * (`MediaReferenceStaleEpoch`) or unsettled (`MediaReferenceEpochUnsettled`). Other rejections, such as locator
 * policy, arrive as `InvalidMediaReference`; they are not epoch problems and are not retried.
 */
internal fun isStaleMediaReferenceEpoch(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }.any {
        it is MarmotKitException.MediaReferenceStaleEpoch || it is MarmotKitException.MediaReferenceEpochUnsettled
    }
