package dev.ipf.whitenoise.android.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Parsed inbound share payload from [Intent.ACTION_SEND] or
 * [Intent.ACTION_SEND_MULTIPLE]. Never log instances — they may carry plaintext
 * user content from another app.
 */
data class SharePayload(
    val text: String?,
    val streamUris: List<Uri>,
    val intentMimeType: String?,
) {
    fun isSupported(): Boolean = !text.isNullOrBlank() || streamUris.isNotEmpty()
}

/** Returns a supported share payload, or null for empty/malformed/unsupported intents. */
fun parseShareIntent(intent: Intent?): SharePayload? {
    intent ?: return null
    return when (intent.action) {
        Intent.ACTION_SEND -> parseSendIntent(intent)
        Intent.ACTION_SEND_MULTIPLE -> parseSendMultipleIntent(intent)
        else -> null
    }
}

private fun parseSendIntent(intent: Intent): SharePayload? {
    val text = intent.extractShareText()
    val streams = intent.extractSingleStream()
    val payload =
        SharePayload(
            text = text,
            streamUris = streams,
            intentMimeType = intent.type?.takeIf { it.isNotBlank() },
        )
    return payload.takeIf { it.isSupported() }
}

private fun parseSendMultipleIntent(intent: Intent): SharePayload? {
    val text = intent.extractShareText()
    val streams = intent.extractMultipleStreams()
    val payload =
        SharePayload(
            text = text,
            streamUris = streams,
            intentMimeType = intent.type?.takeIf { it.isNotBlank() },
        )
    return payload.takeIf { it.isSupported() }
}

private fun Intent.extractShareText(): String? =
    getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun Intent.extractSingleStream(): List<Uri> {
    val stream = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java) ?: return emptyList()
    return listOfNotNull(stream.takeIf { !it.toString().isBlank() })
}

private fun Intent.extractMultipleStreams(): List<Uri> {
    val streamUris = IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
    val fromExtra =
        streamUris
            ?.mapNotNull { uri -> uri.takeIf { !it.toString().isBlank() } }
            .orEmpty()
    val clip = clipData
    return when {
        fromExtra.isNotEmpty() -> fromExtra.distinct()
        clip == null -> emptyList()
        else ->
            buildList {
                for (index in 0 until clip.itemCount) {
                    clip
                        .getItemAt(index)
                        .uri
                        ?.takeIf { !it.toString().isBlank() }
                        ?.let(::add)
                }
            }.distinct()
    }
}
