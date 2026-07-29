package dev.ipf.whitenoise.android.ui

private val speakableUrl = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>)\\]}\"']+")
private val speakableHostLabel =
    Regex(
        "(?i)(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}" +
            "(?::\\d{1,5})?(?:[/#?][^\\s<>()\\[\\]{}\"']*)?",
    )
private val speakableIpv4Label =
    Regex("(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?:[/#?][^\\s<>()\\[\\]{}\"']*)?")
private val speakableWhitespace = Regex("\\s+")
private val emptySpeakableDelimiters = Regex("\\(\\s*\\)|\\[\\s*]|\\{\\s*}")
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
        speakableUrl.replace(this) {
            removedUrl = true
            " "
        }
    return if (removedUrl) {
        withoutUrls.trimEnd().trimEnd(':', ';', ',')
    } else {
        withoutUrls
    }
}

/** True when a markdown link's entire visible label is itself a URL. */
internal fun isSpeakableUrlLabel(label: String): Boolean {
    val candidate = label.trim().trimEnd('.', ',', ';', ':', '!', '?')
    return candidate.isNotEmpty() &&
        (speakableHostLabel.matches(candidate) || speakableIpv4Label.matches(candidate))
}

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
