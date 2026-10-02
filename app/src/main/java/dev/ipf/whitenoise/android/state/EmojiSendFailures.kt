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

/**
 * Whether [throwable] says MDK rejected a media reference because of its epoch, either stale
 * (`MediaReferenceStaleEpoch`) or unsettled (`MediaReferenceEpochUnsettled`). MDK reports both as
 * `InvalidMediaReference` with the same "media reference was encrypted at epoch" text. Other
 * rejections, such as locator policy, are not epoch problems and are not retried.
 */
internal fun isStaleEmojiReference(throwable: Throwable): Boolean =
    generateSequence(throwable) { it.cause }.any {
        it is MarmotKitException.InvalidMediaReference && "media reference was encrypted at epoch" in it.details
    }
