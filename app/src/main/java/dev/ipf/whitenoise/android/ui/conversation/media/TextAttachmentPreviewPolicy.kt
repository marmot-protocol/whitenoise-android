package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.MediaPipeline
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Deliberately below the general receive ceiling: decoded text and Markdown stay cheap to render. */
internal const val TEXT_ATTACHMENT_PREVIEW_MAX_BYTES = 512 * 1024

internal enum class TextAttachmentFormat {
    Markdown,
    PlainText,
}

internal data class TextAttachmentCandidate(
    val displayName: String,
    val normalizedMime: String,
    val format: TextAttachmentFormat,
)

internal enum class TextAttachmentEncoding {
    Utf8,
    Utf16Le,
    Utf16Be,
}

internal sealed interface TextAttachmentDecodeResult {
    data class Success(
        val text: String,
        val encoding: TextAttachmentEncoding,
    ) : TextAttachmentDecodeResult

    data object TooLarge : TextAttachmentDecodeResult

    data object InvalidEncoding : TextAttachmentDecodeResult

    data object Binary : TextAttachmentDecodeResult
}

private val markdownMimes = setOf("text/markdown", "text/x-markdown")
private val markdownExtensions = setOf("md", "markdown")
private val plainTextExtensions = setOf("txt", "text", "log", "csv", "json", "xml", "yaml", "yml")
private val textualApplicationMimes =
    setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
    )
private val mimeTypePattern =
    Regex("[a-z0-9][a-z0-9!#\$&^_.+-]*/[a-z0-9][a-z0-9!#\$&^_.+-]*")

/** Metadata only chooses whether to attempt a preview; decoded bytes remain authoritative. */
internal fun textAttachmentCandidate(
    mediaType: String,
    fileName: String,
): TextAttachmentCandidate? {
    val displayName =
        MediaPipeline
            .safeDisplayName(fileName)
            .filterNot { character -> Character.getType(character) == Character.FORMAT.toInt() }
            .trim()
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "attachment"
    val mime =
        mediaType
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf(mimeTypePattern::matches)
            .orEmpty()
    val extension =
        displayName
            .substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && it.length < displayName.length }
            ?.lowercase(Locale.ROOT)
    val format =
        when {
            mime in markdownMimes || extension in markdownExtensions -> TextAttachmentFormat.Markdown
            mime.startsWith("text/") ||
                mime in textualApplicationMimes ||
                (mime.startsWith("application/") && (mime.endsWith("+json") || mime.endsWith("+xml"))) ||
                extension in plainTextExtensions -> TextAttachmentFormat.PlainText
            else -> return null
        }
    return TextAttachmentCandidate(
        displayName = displayName,
        normalizedMime = mime.ifBlank { "text/plain" },
        format = format,
    )
}

/** Strict, BOM-aware decoding that never constructs a String above the preview byte budget. */
@Suppress("ReturnCount") // Each early return is a fail-closed boundary before later allocation or rendering.
internal fun decodeTextAttachment(
    bytes: ByteArray,
    maxBytes: Int = TEXT_ATTACHMENT_PREVIEW_MAX_BYTES,
): TextAttachmentDecodeResult {
    if (bytes.size > maxBytes) return TextAttachmentDecodeResult.TooLarge
    val (encoding, offset) =
        when {
            bytes.startsWith(UTF8_BOM) -> TextAttachmentEncoding.Utf8 to UTF8_BOM.size
            bytes.startsWith(UTF16_LE_BOM) -> TextAttachmentEncoding.Utf16Le to UTF16_LE_BOM.size
            bytes.startsWith(UTF16_BE_BOM) -> TextAttachmentEncoding.Utf16Be to UTF16_BE_BOM.size
            else -> TextAttachmentEncoding.Utf8 to 0
        }
    val charset =
        when (encoding) {
            TextAttachmentEncoding.Utf8 -> StandardCharsets.UTF_8
            TextAttachmentEncoding.Utf16Le -> StandardCharsets.UTF_16LE
            TextAttachmentEncoding.Utf16Be -> StandardCharsets.UTF_16BE
        }
    val payloadSize = bytes.size - offset
    if (encoding != TextAttachmentEncoding.Utf8 && payloadSize % 2 != 0) {
        return TextAttachmentDecodeResult.InvalidEncoding
    }
    val text =
        try {
            charset
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, payloadSize))
                .toString()
        } catch (_: CharacterCodingException) {
            return TextAttachmentDecodeResult.InvalidEncoding
        }
    if (text.any(::isDisallowedTextControl)) return TextAttachmentDecodeResult.Binary
    return TextAttachmentDecodeResult.Success(text = text, encoding = encoding)
}

private fun isDisallowedTextControl(character: Char): Boolean =
    Character.isISOControl(character) &&
        character != '\n' &&
        character != '\r' &&
        character != '\t'

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { index -> this[index] == prefix[index] }
}

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
