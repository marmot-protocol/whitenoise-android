package dev.ipf.whitenoise.android.ui

import java.net.URI
import java.util.Locale

private val speakableUrl = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\\]}\"']+")
private val speakableScheme = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
private val speakableWhitespace = Regex("\\s+")
private val emptySpeakableDelimiters = Regex("\\(\\s*\\)|\\[\\s*\\]|\\{\\s*\\}")
private val spaceBeforeSpeakablePunctuation = Regex("\\s+([,.;:!?])")

/** URL-safe last-resort projection when legacy content has no usable Markdown AST. */
internal fun legacyTextToSpeakableText(text: String): String {
    val visible = markdownSafeDisplayText(text, MARKDOWN_SPEAKABLE_MAX_LENGTH)
    val output = StringBuilder(minOf(MARKDOWN_SPEAKABLE_MAX_LENGTH, 256))
    for (line in visible.lineSequence()) {
        if (output.length >= MARKDOWN_SPEAKABLE_MAX_LENGTH) break
        output.appendSpeakableSegment(line.withoutSpeakableUrls())
    }
    return output.toString().trimEnd()
}

internal fun markdownSpeakableLeafText(
    content: String,
    maxChars: Int,
): String {
    if (maxChars <= 0) return ""
    val visible = markdownSafeDisplayText(content, maxChars)
    return visible.withoutSpeakableUrls().safeUtf16Prefix(maxChars)
}

private fun String.withoutSpeakableUrls(): String {
    var removedUrl = false
    val withoutUrls =
        speakableUrl.replace(this) { match ->
            removedUrl = true
            " ${match.value.unmatchedClosingParenthesisSuffix()}"
        }
    return if (removedUrl) {
        withoutUrls.trimEnd().trimEnd(':', ';', ',')
    } else {
        withoutUrls
    }
}

private fun String.unmatchedClosingParenthesisSuffix(): String {
    var openParentheses = 0
    forEachIndexed { index, character ->
        when (character) {
            '(' -> openParentheses++
            ')' ->
                if (openParentheses == 0) {
                    return substring(index)
                } else {
                    openParentheses--
                }
        }
    }
    return ""
}

/** True when a web link's entire visible label names the destination host. */
internal fun isSpeakableUrlLabel(
    label: String,
    destination: String,
    destinationIsWeb: Boolean,
): Boolean {
    val candidate = label.trim().trimEnd('.', ',', ';', ':', '!', '?')
    val labelHost = candidate.takeIf { destinationIsWeb && it.isNotEmpty() }?.speakableWebHost()
    val destinationHost = destination.takeIf { destinationIsWeb }?.speakableWebHost()
    return labelHost != null && labelHost == destinationHost
}

private fun String.speakableWebHost(): String? =
    runCatching {
        val url =
            when {
                startsWith("//") -> "https:$this"
                speakableScheme.containsMatchIn(this) -> this
                else -> "https://$this"
            }
        URI(url)
            .host
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("www.")
            ?.takeIf(String::isNotEmpty)
    }.getOrNull()

internal fun StringBuilder.appendSpeakableSegment(segment: String) {
    val sentence = segment.asSpeakableSentence()
    if (sentence.isEmpty() || length >= MARKDOWN_SPEAKABLE_MAX_LENGTH) return
    if (isNotEmpty()) append(' ')
    append(sentence.safeUtf16Prefix(MARKDOWN_SPEAKABLE_MAX_LENGTH - length))
}

private fun String.asSpeakableSentence(): String {
    val normalized =
        trim()
            .replace(emptySpeakableDelimiters, " ")
            .replace(speakableWhitespace, " ")
            .replace(spaceBeforeSpeakablePunctuation, "$1")
            .trim()
    if (normalized.isEmpty()) return normalized
    return if (normalized.last() in ".!?;:,") normalized else "$normalized."
}

internal fun String.safeUtf16Prefix(maxChars: Int): String =
    when {
        length <= maxChars -> this
        maxChars <= 0 -> ""
        else -> {
            var end = maxChars
            if (this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
            substring(0, end)
        }
    }
