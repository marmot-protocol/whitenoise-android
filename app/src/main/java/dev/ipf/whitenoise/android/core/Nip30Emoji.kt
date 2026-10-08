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
    private val sendableCodePattern = Regex("[A-Za-z0-9_]{1,62}")

    /** MDK's per-event tag row limit. */
    const val MAX_TAGS = 64

    /** MDK's limit on the combined length of every tag value, in UTF-8 bytes. */
    const val MAX_TAG_VALUE_BYTES = 16 * 1024

    // Legacy plaintext surfaces have no AST. Keep raw code delimiters and their contents literal.
    val rawCodePattern =
        Regex(
            """(?m)^ {0,3}((?>`{3,}))[^\n`]*(?:\n|\z)[\s\S]*?(?:^ {0,3}\1`*[ \t]*\r?$|\z)|""" +
                """^ {0,3}((?>~{3,}))[^\n]*(?:\n|\z)[\s\S]*?(?:^ {0,3}\2~*[ \t]*\r?$|\z)|""" +
                """(`+)[\s\S]*?\3""",
        )

    /** Whether [tags] carry any emoji tag, so they are the event's own tags rather than a stripped projection. */
    fun hasEmojiTags(tags: List<MessageTagFfi>): Boolean = tags.any { it.values.firstOrNull() == TAG_NAME }

    /**
     * The `:code:`s each image attachment defines, keyed by protocol attachment index and in tag
     * order. Aliases of one image share its attachment, so one attachment may carry several codes.
     * The first well-formed tag for a code wins even when it names no attachment, so a repeated
     * code with another URL cannot rebind it. Every matching image slot is claimed, matching native
     * attachment-history roles; renderers may still choose the first image for each shortcode.
     */
    fun attachmentShortcodes(
        tags: List<MessageTagFfi>,
        attachments: List<IndexedAttachment>,
    ): Map<Int, List<String>> {
        val images = attachments.filter { MediaReferenceSupport.isImageMedia(it.value) }
        if (images.isEmpty()) {
            return emptyMap()
        }
        val defined = linkedMapOf<Int, MutableList<String>>()
        val seen = mutableSetOf<String>()
        for ((shortcode, url) in tags.mapNotNull(::shortcodeAndUrl)) {
            if (!seen.add(shortcode)) {
                continue
            }
            for ((index, reference) in images) {
                if (reference.locators.any { it.value == url }) {
                    defined.getOrPut(index) { mutableListOf() }.add(shortcode)
                }
            }
        }
        return defined
    }

    /** Whether [shortcode] (`:code:`) is within the alphabet and length a sent emoji tag may use. */
    fun isSendable(shortcode: String): Boolean = sendableCodePattern.matches(shortcode.trim(':'))

    /**
     * Every distinct `:code:` in [text], in first-appearance order. Codes inside inline code spans
     * and fenced blocks are literal text, the same as when rendered, so they never attach artwork.
     */
    fun shortcodesIn(text: String): List<String> {
        val codeRanges = rawCodePattern.findAll(text).map { it.range }.toList()
        return shortcodeInText
            .findAll(text)
            .filter { match -> codeRanges.none { match.range.first <= it.last && match.range.last >= it.first } }
            .map { it.value }
            .distinct()
            .toList()
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
