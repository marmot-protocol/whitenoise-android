@file:Suppress("MatchingDeclarationName") // One outbound boundary owns its stream type and intent helpers.

package dev.ipf.whitenoise.android.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.presentFailure
import dev.ipf.whitenoise.android.state.runCatchingCancellable

internal data class OutboundShareStream(
    val uri: Uri,
    val mediaType: String,
)

private const val MIN_PRINTABLE_ASCII = 0x21
private const val MAX_PRINTABLE_ASCII = 0x7e

/** Build the exact Android send contract for text, one stream, or many streams. */
internal fun outboundShareIntent(
    text: String?,
    streams: List<OutboundShareStream>,
): Intent {
    val visibleText = text?.takeIf { it.isNotBlank() }
    require(visibleText != null || streams.isNotEmpty()) { "Outbound share requires visible content" }

    return Intent(if (streams.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = outboundShareMimeType(streams)
        visibleText?.let { putExtra(Intent.EXTRA_TEXT, it) }
        when (streams.size) {
            0 -> Unit
            1 -> putExtra(Intent.EXTRA_STREAM, streams.single().uri)
            else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(streams.map(OutboundShareStream::uri)))
        }
        if (streams.isNotEmpty()) {
            clipData =
                ClipData.newRawUri("shared attachment", streams.first().uri).also { clip ->
                    streams.drop(1).forEach { stream -> clip.addItem(ClipData.Item(stream.uri)) }
                }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

internal fun outboundShareMimeType(streams: List<OutboundShareStream>): String {
    val types = streams.map { normalizedOutboundMediaType(it.mediaType) }.distinct()
    val topLevels = types.mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }.distinct()
    return when {
        streams.isEmpty() -> "text/plain"
        types.size == 1 -> types.single()
        topLevels.size == 1 && types.all { '/' in it } -> "${topLevels.single()}/*"
        else -> "*/*"
    }
}

internal fun normalizedOutboundMediaType(mediaType: String): String {
    val normalized = mediaType.trim().lowercase()
    val slash = normalized.indexOf('/')
    return normalized.takeIf {
        slash > 0 &&
            slash < normalized.lastIndex &&
            slash == normalized.lastIndexOf('/') &&
            normalized.none(Char::isWhitespace) &&
            normalized.all { character -> character.code in MIN_PRINTABLE_ASCII..MAX_PRINTABLE_ASCII }
    } ?: "application/octet-stream"
}

/**
 * Wrap a send intent in the system chooser while excluding this app's inbound
 * share activity and Direct Share shortcuts. The explicit external-target
 * check avoids presenting an empty chooser or looping content back into White
 * Noise on devices where it is the only matching target.
 */
@Suppress("DEPRECATION")
internal fun outboundShareChooser(
    context: Context,
    sendIntent: Intent,
    title: String,
): Intent {
    val matches = context.packageManager.queryIntentActivities(sendIntent, PackageManager.MATCH_DEFAULT_ONLY)
    if (matches.none { it.activityInfo.packageName != context.packageName }) {
        throw ActivityNotFoundException("No external share target")
    }
    val ownComponents =
        buildSet {
            add(ComponentName(context, MainActivity::class.java))
            matches
                .filter { it.activityInfo.packageName == context.packageName }
                .forEach { add(ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
        }
    return Intent.createChooser(sendIntent, title).apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownComponents.toTypedArray())
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal fun launchOutboundShare(
    context: Context,
    sendIntent: Intent,
    title: String,
): Result<Unit> =
    runCatchingCancellable {
        context.startActivity(outboundShareChooser(context, sendIntent, title))
    }

internal fun inviteShareIntent(message: String): Intent = outboundShareIntent(message, emptyList())

internal fun launchInviteShare(
    context: Context,
    message: String,
    title: String,
): Result<Unit> = launchOutboundShare(context, inviteShareIntent(message), title)

/** Expected target absence stays terse; other failures retain a privacy-safe diagnostic report. */
internal fun WhiteNoiseAppState.presentOutboundShareFailure(
    operationCode: String,
    throwable: Throwable,
) {
    if (throwable is ActivityNotFoundException) {
        present(R.string.no_share_target_available)
    } else {
        presentFailure(R.string.outbound_share_failed, operationCode, throwable)
    }
}
