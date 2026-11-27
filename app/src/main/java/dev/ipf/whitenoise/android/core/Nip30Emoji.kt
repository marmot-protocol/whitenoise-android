package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.media.MediaReferenceSupport

/**
 * NIP-30 custom emoji over Marmot: an `["emoji", code, url]` tag names the message's own encrypted
 * image attachment whose locator is `url`. That attachment is the artwork for `:code:` in the same
 * event, not a file the sender shared.
 */
object Nip30Emoji {
    private const val TAG_NAME = "emoji"
    private const val TAG_SIZE = 3
    private const val TAG_SIZE_WITH_SET = 4
    private val codePattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val shortcodeInText = Regex(":($codePattern):")

    /** MDK's per-event tag row limit. */
    const val MAX_TAGS = 64

    /** MDK's limit on the combined length of every tag value, in UTF-8 bytes. */
    const val MAX_TAG_VALUE_BYTES = 16 * 1024

    private const val MAX_FENCE_INDENT = 3
    private const val MIN_FENCE_LENGTH = 3

    /**
     * Ranges of fenced blocks and inline code spans in [text], sorted and disjoint, for legacy
     * plaintext surfaces that have no Markdown AST. A fence opens on a line indented at most three
     * spaces with three or more backticks (no backtick after them) or tildes, and closes on a later
     * line with at least as many of the same character, or at the end of the text. An inline span
     * closes on the next backtick run of exactly its length; a run with no partner stays text.
     *
     * Received text is untrusted, so this is one linear pass: a backtracking pattern took seconds
     * on long backtick runs.
     */
    fun rawCodeRanges(text: String): List<IntRange> {
        val closers = closingBacktickRuns(text)
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index < text.length) {
            if (index == 0 || text[index - 1] == '\n') {
                val fenceEnd = fenceEnd(text, index)
                if (fenceEnd != null) {
                    ranges.add(index until fenceEnd)
                    index = fenceEnd
                    continue
                }
            }
            if (text[index] != '`') {
                index++
                continue
            }
            val runEnd = runEnd(text, index, '`')
            val closer = closers[index]
            if (closer == null) {
                index = runEnd
            } else {
                val spanEnd = closer + (runEnd - index)
                ranges.add(index until spanEnd)
                index = spanEnd
            }
        }
        return ranges
    }

    /** For each backtick run's start, the start of the next run with exactly the same length. */
    private fun closingBacktickRuns(text: String): Map<Int, Int> {
        val runs = mutableListOf<IntRange>()
        var index = text.indexOf('`')
        while (index >= 0) {
            val end = runEnd(text, index, '`')
            runs.add(index until end)
            index = text.indexOf('`', end)
        }
        val closers = HashMap<Int, Int>()
        val nextByLength = HashMap<Int, Int>()
        for (run in runs.asReversed()) {
            val length = run.last - run.first + 1
            nextByLength[length]?.let { closers[run.first] = it }
            nextByLength[length] = run.first
        }
        return closers
    }

    /** End (exclusive) of the fenced block opening on the line at [lineStart], or null when no fence opens there. */
    private fun fenceEnd(
        text: String,
        lineStart: Int,
    ): Int? {
        val markerStart = lineStart + leadingSpaces(text, lineStart)
        val marker = text.getOrNull(markerStart)?.takeIf { it == '`' || it == '~' } ?: return null
        val markerEnd = runEnd(text, markerStart, marker)
        val length = markerEnd - markerStart
        if (length < MIN_FENCE_LENGTH) {
            return null
        }
        val lineEnd = lineEnd(text, markerEnd)
        if (marker == '`' && (markerEnd until lineEnd).any { text[it] == '`' }) {
            return null
        }
        var line = lineEnd + 1
        while (line < text.length) {
            val end = lineEnd(text, line)
            if (closesFence(text, line, end, marker, length)) {
                return end
            }
            line = end + 1
        }
        return text.length
    }

    private fun closesFence(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        marker: Char,
        length: Int,
    ): Boolean {
        val markerStart = lineStart + leadingSpaces(text, lineStart)
        val markerEnd = runEnd(text, markerStart, marker).coerceAtMost(lineEnd)
        if (markerEnd - markerStart < length) {
            return false
        }
        val rest = text.substring(markerEnd, lineEnd).removeSuffix("\r")
        return rest.all { it == ' ' || it == '\t' }
    }

    private fun leadingSpaces(
        text: String,
        lineStart: Int,
    ): Int {
        var count = 0
        while (count < MAX_FENCE_INDENT && text.getOrNull(lineStart + count) == ' ') {
            count++
        }
        return count
    }

    private fun lineEnd(
        text: String,
        from: Int,
    ): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    private fun runEnd(
        text: String,
        start: Int,
        char: Char,
    ): Int {
        var end = start
        while (end < text.length && text[end] == char) {
            end++
        }
        return end
    }

    /** Whether [tags] carry any emoji tag, so they are the event's own tags rather than a stripped projection. */
    fun hasEmojiTags(tags: List<MessageTagFfi>): Boolean = tags.any { it.values.firstOrNull() == TAG_NAME }

    /**
     * The `:code:`s each image attachment defines, keyed by protocol attachment index and in tag
     * order. Aliases of one image share its attachment, so one attachment may carry several codes.
     * The first well-formed tag for a code wins even when it names no attachment, so a repeated
     * code with another URL cannot rebind it. Every matching image slot is claimed, matching native
     * attachment-history roles; renderers may still choose the first image for each shortcode.
     *
     * Received tags and locators are untrusted and unbounded, so only the first [MAX_TAGS] codes and
     * images are considered, and locators are indexed once instead of rescanned per code. Anything
     * past that budget keeps its text and ordinary attachment fallbacks.
     */
    fun attachmentShortcodes(
        tags: List<MessageTagFfi>,
        attachments: List<IndexedAttachment>,
    ): Map<Int, List<String>> {
        val images =
            attachments
                .asSequence()
                .filter { MediaReferenceSupport.isImageMedia(it.value) }
                .take(MAX_TAGS)
                .toList()
        if (images.isEmpty()) {
            return emptyMap()
        }
        val imagesByLocator = HashMap<String, MutableList<Int>>()
        for ((position, image) in images.withIndex()) {
            for (locator in image.value.locators) {
                val claimants = imagesByLocator.getOrPut(locator.value) { mutableListOf() }
                if (claimants.lastOrNull() != position) {
                    claimants.add(position)
                }
            }
        }
        val defined = linkedMapOf<Int, MutableList<String>>()
        tags
            .asSequence()
            .mapNotNull(::shortcodeAndUrl)
            .distinctBy { (shortcode, _) -> shortcode }
            .take(MAX_TAGS)
            .forEach { (shortcode, url) ->
                imagesByLocator[url]?.forEach { position ->
                    defined.getOrPut(images[position].index) { mutableListOf() }.add(shortcode)
                }
            }
        return defined
    }

    /** Whether [shortcode] (`:code:`) is within the alphabet and length a sent emoji tag may use. */
    fun isSendable(shortcode: String): Boolean = codePattern.matches(shortcode.trim(':'))

    /**
     * Every distinct `:code:` in [text], in first-appearance order. Codes inside inline code spans
     * and fenced blocks are literal text, the same as when rendered, so they never attach artwork.
     */
    fun shortcodesIn(text: String): List<String> {
        val matches = shortcodeInText.findAll(text).toList()
        if (matches.isEmpty()) {
            return emptyList()
        }
        val outsideCode = outsideRanges(rawCodeRanges(text))
        return matches
            .filter { outsideCode(it.range.first, it.range.last + 1) }
            .map { it.value }
            .distinct()
    }

    /**
     * A test for whether `[start, end)` misses every one of the sorted, disjoint [ranges]. Calls
     * must come in increasing order of [start], so a text's matches are checked in one pass.
     */
    fun outsideRanges(ranges: List<IntRange>): (start: Int, end: Int) -> Boolean {
        var next = 0
        return { start, end ->
            while (next < ranges.size && ranges[next].last < start) {
                next++
            }
            next == ranges.size || ranges[next].first >= end
        }
    }

    /** The `["emoji", code, url]` row naming an attachment by its first locator, for a `:code:` shortcode. */
    fun tag(
        shortcode: String,
        url: String,
    ): List<String> = listOf(TAG_NAME, shortcode.trim(':'), url)

    /** `:code:` and url of a well-formed emoji tag. */
    private fun shortcodeAndUrl(tag: MessageTagFfi): Pair<String, String>? {
        val values = tag.values
        if (values.size !in TAG_SIZE..TAG_SIZE_WITH_SET || values[0] != TAG_NAME) {
            return null
        }
        return (":${values[1]}:" to values[2]).takeIf { codePattern.matches(values[1]) && values[2].isNotBlank() }
    }
}
