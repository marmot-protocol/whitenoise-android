package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownDocumentFfi

/** Bounded cache for message-level mention classification on the Compose thread. */
internal class MentionDetectionCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0)
    }

    private data class Entry(
        val document: MarkdownDocumentFfi,
        val mentionsAccount: Boolean,
    )

    private val entries = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

    fun getOrCompute(
        messageIdHex: String,
        document: MarkdownDocumentFfi,
        detector: () -> Boolean,
    ): Boolean {
        val cached = entries[messageIdHex]
        if (cached != null && (cached.document === document || cached.document == document)) {
            return cached.mentionsAccount
        }
        val result = detector()
        entries[messageIdHex] = Entry(document, result)
        while (entries.size > maxEntries) {
            val eldestKey = entries.entries.firstOrNull()?.key ?: break
            entries.remove(eldestKey)
        }
        return result
    }

    internal fun sizeForTests(): Int = entries.size

    private companion object {
        private const val DEFAULT_MAX_ENTRIES = 512
    }
}
