package dev.ipf.whitenoise.android.notifications

/** Pure parsing helpers for payload-filtered SystemUI heads-up diagnostics. */
internal object HeadsUpSystemUiDiagnostics {
    private const val HEADS_UP_MANAGER_HEADER = "HeadsUpManagerPhone state:"

    /** Exact-key rows appended after a stable diagnostic-buffer baseline. */
    data class ExactTargetLineDelta(
        val lines: List<String>,
        val baselineStable: Boolean,
    )

    /**
     * Returns the indented body of `HeadsUpManagerPhone state:` only. A sibling
     * StatusBar section can contain the same shade-card key after heads-up has
     * collapsed, so parsing stops at the next nonblank line at header depth.
     */
    fun headsUpManagerPhoneBlock(statusBarDump: String): List<String> {
        val lines = statusBarDump.lineSequence().toList()
        val headerIndex = lines.indexOfFirst { it.trim() == HEADS_UP_MANAGER_HEADER }
        if (headerIndex < 0) return emptyList()
        val headerIndent = lines[headerIndex].leadingWhitespaceCount()
        return lines
            .drop(headerIndex + 1)
            .takeWhile { line -> line.isBlank() || line.leadingWhitespaceCount() > headerIndent }
    }

    /** Exports only rows in the heads-up block that contain the exact synthetic key. */
    fun exactTargetLines(
        statusBarDump: String,
        key: String,
    ): List<String> = headsUpManagerPhoneBlock(statusBarDump).filter { lineContainsExactTargetKey(it, key) }

    /**
     * Matches a complete framework key in a `key=` field, event-log list, or
     * HeadsUpManager log sentence, without accepting key extensions.
     */
    fun lineContainsExactTargetKey(
        line: String,
        key: String,
    ): Boolean {
        if (key.isEmpty()) return false
        val escapedKey = Regex.escape(key)
        val keyFieldOrEventEntry =
            Regex("(?:\\bkey\\s*[=:]\\s*|\\[\\s*|^|\\s)$escapedKey(?=$|[\\s,\\]})])")
        return keyFieldOrEventEntry.containsMatchIn(line)
    }

    /**
     * Filters unrelated keys before comparing a rolling event/log buffer, so
     * another notification cannot make the target baseline look unstable.
     */
    fun exactTargetDelta(
        before: List<String>,
        after: List<String>,
        key: String,
    ): ExactTargetLineDelta {
        val matchingBefore = before.filter { lineContainsExactTargetKey(it, key) }
        val matchingAfter = after.filter { lineContainsExactTargetKey(it, key) }
        val baselineStable =
            matchingAfter.size >= matchingBefore.size &&
                matchingAfter.take(matchingBefore.size) == matchingBefore
        val appended = if (baselineStable) matchingAfter.drop(matchingBefore.size) else emptyList()
        return ExactTargetLineDelta(
            lines = appended,
            baselineStable = baselineStable,
        )
    }

    /** Counts indentation without trimming diagnostic field values. */
    private fun String.leadingWhitespaceCount(): Int =
        indexOfFirst { !it.isWhitespace() }.let { firstContent ->
            if (firstContent < 0) length else firstContent
        }
}
