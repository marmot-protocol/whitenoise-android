package dev.ipf.whitenoise.android.core.nostr

/** Benchmark-only copy of the former Kotlin canonicalization path. */
internal fun NostrEvent.canonicalJson(): String =
    buildString {
        append('[')
        append('0')
        append(',')
        appendNostrJsonString(pubkey)
        append(',')
        append(createdAt)
        append(',')
        append(kind)
        append(',')
        append('[')
        tags.forEachIndexed { index, tag ->
            if (index > 0) append(',')
            append('[')
            tag.forEachIndexed { tagIndex, value ->
                if (tagIndex > 0) append(',')
                appendNostrJsonString(value)
            }
            append(']')
        }
        append(']')
        append(',')
        appendNostrJsonString(content)
        append(']')
    }

internal fun NostrEvent.computedIdHex(): String = sha256(canonicalJson().toByteArray(Charsets.UTF_8)).toHex()

private fun StringBuilder.appendNostrJsonString(value: String) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char < ' ') {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}
