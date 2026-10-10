package dev.ipf.whitenoise.android.ui.settings

import android.content.res.Resources
import dev.ipf.whitenoise.android.R
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The existing build-generated notice text and byte offsets, displayed without the Google activity SDK. */
internal data class OpenSourceNotice(
    val id: String,
    val name: String,
    val text: String,
)

internal fun readOpenSourceNotices(
    resources: Resources,
    packageName: String,
): List<OpenSourceNotice> {
    val metadata = resources.getIdentifier("third_party_license_metadata", "raw", packageName)
    val texts = resources.getIdentifier("third_party_licenses", "raw", packageName)
    require(metadata != 0 && texts != 0) { "Missing generated open source notices" }
    val index = resources.openRawResource(metadata).use { stream -> stream.readBytes() }
    val data = resources.openRawResource(texts).use { stream -> stream.readBytes() }
    val generated = parseOpenSourceNotices(index, data)
    val local = resources.openRawResource(R.raw.material_icons_notice).use { stream -> strictUtf8(stream.readBytes()) }
    return (generated + OpenSourceNotice("local-material-icons", "AndroidX Material icon definitions", local))
        .sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
}

/** Offsets are bytes, not UTF-16 characters; malformed/truncated notices fail visibly instead of disappearing. */
internal fun parseOpenSourceNotices(
    metadata: ByteArray,
    texts: ByteArray,
): List<OpenSourceNotice> {
    require(metadata.size <= MAX_METADATA_BYTES && texts.size <= MAX_LICENSE_BYTES)
    val entries =
        strictUtf8(metadata)
            .lineSequence()
            .filter(String::isNotBlank)
            .map { line ->
                val separator = line.indexOf(' ')
                require(separator > 0)
                val range = line.substring(0, separator).split(':')
                require(range.size == 2)
                val offset = range[0].toLongOrNull()
                val length = range[1].toLongOrNull()
                val name = line.substring(separator + 1).trim()
                require(offset != null && length != null && offset >= 0 && length > 0 && name.isNotEmpty())
                require(offset <= texts.size.toLong() && length <= texts.size.toLong() - offset)
                val text = strictUtf8(texts.copyOfRange(offset.toInt(), (offset + length).toInt()))
                require(text.isNotBlank())
                OpenSourceNotice("$offset:$length:$name", name, text)
            }.toList()
    require(entries.isNotEmpty() && entries.size <= MAX_NOTICES)
    return entries.distinctBy(OpenSourceNotice::id).sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
}

private fun strictUtf8(bytes: ByteArray): String =
    Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private const val MAX_METADATA_BYTES = 1_048_576
private const val MAX_LICENSE_BYTES = 16_777_216
private const val MAX_NOTICES = 4096
