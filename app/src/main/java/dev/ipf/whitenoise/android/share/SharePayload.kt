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
    val importReady: Boolean = false,
    val importErrors: List<ShareImportError> = emptyList(),
    val importRejectedCount: Int = 0,
) {
    /** A nonblank caption, stream, or typed recovery error makes the request actionable without sending it. */
    fun isSupported(): Boolean = !text.isNullOrBlank() || streamUris.isNotEmpty() || importErrors.isNotEmpty()
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

/** Extracts one grant URI and unmodified nonblank text from ACTION_SEND without accessing the provider. */
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

/** Preserves multiple-URI ordering and caption formatting; actual access and byte budgets belong to intake. */
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

/** Keeps the exact nonblank CharSequence text, including indentation and trailing newlines. */
private fun Intent.extractShareText(): String? =
    getCharSequenceExtra(Intent.EXTRA_TEXT)
        ?.toString()
        ?.takeIf { it.isNotBlank() }

/** Reads a typed nonempty URI extra without opening its temporary grant. */
private fun Intent.extractSingleStream(): List<Uri> {
    val stream = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java) ?: return emptyList()
    return listOfNotNull(stream.takeIf { !it.toString().isBlank() })
}

/** Uses typed stream extras or ClipData fallback, dropping empty duplicates while preserving first occurrence order. */
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
