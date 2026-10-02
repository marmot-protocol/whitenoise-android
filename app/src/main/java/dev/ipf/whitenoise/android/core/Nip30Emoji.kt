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
    private val codePattern = Regex("[A-Za-z0-9_-]{1,64}")
    private val shortcodeInText = Regex(":($codePattern):")

    /** Whether [tags] carry any emoji tag, so they are the event's own tags rather than a stripped projection. */
    fun hasEmojiTags(tags: List<MessageTagFfi>): Boolean = tags.any { it.values.firstOrNull() == TAG_NAME }

    /**
     * The `:code:` each image attachment defines, keyed by protocol attachment index. The first tag
     * naming an attachment wins, and a code keeps the first attachment it names.
     */
    fun attachmentShortcodes(
        tags: List<MessageTagFfi>,
        attachments: List<IndexedAttachment>,
    ): Map<Int, String> {
        val images = attachments.filter { MediaReferenceSupport.isImageMedia(it.value) }
        if (images.isEmpty()) {
            return emptyMap()
        }
        val defined = linkedMapOf<Int, String>()
        for ((shortcode, url) in tags.mapNotNull(::shortcodeAndUrl)) {
            if (shortcode in defined.values) {
                continue
            }
            val attachment =
                images.firstOrNull { (index, reference) ->
                    index !in defined && reference.locators.any { it.value == url }
                }
            if (attachment != null) {
                defined[attachment.index] = shortcode
            }
        }
        return defined
    }

    /** Every distinct `:code:` in [text], in first-appearance order. */
    fun shortcodesIn(text: String): List<String> =
        shortcodeInText
            .findAll(text)
            .map { it.value }
            .distinct()
            .toList()

    /** The `["emoji", code, url]` row naming an attachment by its first locator, for a `:code:` shortcode. */
    fun tag(
        shortcode: String,
        url: String,
    ): List<String> = listOf(TAG_NAME, shortcode.trim(':'), url)

    /** `:code:` and url of a well-formed emoji tag. */
    private fun shortcodeAndUrl(tag: MessageTagFfi): Pair<String, String>? {
        val values = tag.values
        if (values.size < TAG_SIZE || values[0] != TAG_NAME) {
            return null
        }
        return (":${values[1]}:" to values[2]).takeIf { codePattern.matches(values[1]) && values[2].isNotBlank() }
    }
}
