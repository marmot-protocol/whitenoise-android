package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import java.util.Locale

/** The emoji send would exceed MDK's tag limits, so nothing was uploaded or sent. */
internal class EmojiSendLimitException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The upload stage of a custom-emoji send failed, so nothing was published. Wrapping the cause keeps
 * a connection reset during upload from being read as an uncertain delivery, since it is a definite
 * failure the user can retry.
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

/** Whether [throwable] is an upload-stage failure that a connectivity recovery can retry without duplicating. */
internal fun isRetryableEmojiUploadFailure(throwable: Throwable): Boolean {
    val chain = generateSequence(throwable) { it.cause }.toList()
    if (chain.none { it is EmojiUploadFailure }) return false
    val text = chain.joinToString("\n") { "${it.javaClass.simpleName} ${it.message}" }.lowercase(Locale.ROOT)
    return chain.any { it is MarmotKitException.TransportClosed } ||
        listOf("connection", "timed out", "timeout", "no relay", "network", "unreachable").any(text::contains)
}

/** Whether [throwable] says MDK rejected a media reference, for example one from an earlier epoch. */
internal fun isStaleEmojiReference(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }.any {
        it is MarmotKitException.InvalidMediaReference || it.message?.contains("StaleEpoch", ignoreCase = true) == true
    }
