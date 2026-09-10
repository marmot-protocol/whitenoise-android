package dev.ipf.whitenoise.android.ui

/** Presentation-only styles; plain notification text never initializes Compose text classes. */
internal enum class MarkdownPreviewStyle { Code, Bold, Italic, Strike }

/** A half-open styled range in the shared UTF-16 preview projection. */
internal data class MarkdownPreviewRange(
    val style: MarkdownPreviewStyle,
    val start: Int,
    val end: Int,
)

/** Immutable visible text and optional styling consumed by the chat-row adapter. */
internal data class MarkdownPreviewProjection(
    val text: String,
    val ranges: List<MarkdownPreviewRange>,
) {
    val length: Int get() = text.length

    /** Tests whether a leaf contributes visible text before adding a block separator. */
    fun isEmpty(): Boolean = text.isEmpty()

    /** Clips both text and intersecting styles at an already surrogate-safe boundary. */
    fun subSequence(
        start: Int,
        end: Int,
    ): MarkdownPreviewProjection =
        if (start == 0 && end == length) {
            this
        } else {
            MarkdownPreviewProjection(
                text.substring(start, end),
                ranges
                    .filter {
                        ((it.start == it.end || start == end) && it.start == start) ||
                            (it.start < end && start < it.end)
                    }.map { it.copy(start = maxOf(start, it.start) - start, end = minOf(end, it.end) - start) },
            )
        }
}

/** One short-lived preview builder; styles are collected only for a styled UI caller. */
internal class MarkdownPreviewBuilder(
    val captureStyles: Boolean,
) {
    private val text = StringBuilder()
    private val ranges = mutableListOf<MarkdownPreviewRange>()
    val length: Int get() = text.length

    /** Appends visible text from one bounded AST leaf. */
    fun append(value: String) {
        text.append(value)
    }

    /** Appends a separator or a mention prefix. */
    fun append(value: Char) {
        text.append(value)
    }

    /** Carries a leaf's optional styles into the document's coordinate space. */
    fun append(value: MarkdownPreviewProjection) {
        val offset = length
        if (captureStyles) ranges += value.ranges.map { it.copy(start = it.start + offset, end = it.end + offset) }
        text.append(value.text)
    }

    /** Retains nesting order without creating any platform or Compose style object. */
    fun withStyle(
        style: MarkdownPreviewStyle,
        block: MarkdownPreviewBuilder.() -> Unit,
    ) {
        val index = ranges.size
        val start = length
        if (captureStyles) ranges += MarkdownPreviewRange(style, start, start)
        block()
        if (captureStyles) ranges[index] = MarkdownPreviewRange(style, start, length)
    }

    /** Freezes the builder before joining or clipping a segment. */
    fun build(): MarkdownPreviewProjection = MarkdownPreviewProjection(text.toString(), ranges.toList())
}

/** Builds a bounded text projection without touching the styled renderer. */
internal inline fun buildMarkdownPreviewText(
    captureStyles: Boolean,
    block: MarkdownPreviewBuilder.() -> Unit,
): MarkdownPreviewProjection = MarkdownPreviewBuilder(captureStyles).apply(block).build()
