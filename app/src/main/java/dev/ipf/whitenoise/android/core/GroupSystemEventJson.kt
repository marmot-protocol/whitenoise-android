package dev.ipf.whitenoise.android.core

import org.json.JSONException
import org.json.JSONObject

/**
 * JVM-safe result of parsing raw kind-1210 JSON.
 *
 * Raw payloads are peer-authored. Consequently this type deliberately keeps
 * attribution null and marks every result unauthenticated; only MarmotKit's
 * state projection may populate those fields later in [GroupSystemEvents].
 */
data class GroupSystemEvent(
    val systemType: String,
    val text: String,
    val actor: String?,
    val subject: String?,
    val name: String?,
    val oldName: String? = null,
    val oldNameKnown: Boolean = oldName != null,
    val oldRetentionSeconds: ULong? = null,
    val newRetentionSeconds: ULong? = null,
    val fromAuthenticatedStateProjection: Boolean,
)

/** Pure, bounded JSON parser shared by the Android projection and `:fuzz`. */
object GroupSystemEventJson {
    const val MAX_INPUT_BYTES = 64 * 1024
    const val MAX_COLLECTION_ELEMENTS = 64
    const val MAX_JSON_DEPTH = 16

    fun parse(plaintext: String): GroupSystemEvent? =
        if (withinResourceBounds(plaintext)) {
            parseBounded(plaintext)
        } else {
            null
        }

    private fun parseBounded(plaintext: String): GroupSystemEvent? =
        try {
            val json = JSONObject(plaintext)
            val systemType = json.optString("system_type").takeIf { it.isNotBlank() }
            systemType?.let {
                val data = json.optJSONObject("data")
                val hasOldName = data?.has("old_name") == true
                GroupSystemEvent(
                    systemType = it,
                    text = json.optString("text"),
                    actor = null,
                    subject = null,
                    name = data?.optString("name")?.takeIf { name -> name.isNotBlank() },
                    oldName = data?.optString("old_name")?.takeIf { name -> name.isNotBlank() },
                    oldNameKnown = hasOldName,
                    oldRetentionSeconds =
                        data
                            ?.optLong("old_retention_seconds", -1L)
                            ?.takeIf { seconds -> seconds >= 0L }
                            ?.toULong(),
                    newRetentionSeconds =
                        data
                            ?.optLong("new_retention_seconds", -1L)
                            ?.takeIf { seconds -> seconds >= 0L }
                            ?.toULong(),
                    fromAuthenticatedStateProjection = false,
                )
            }
        } catch (_: JSONException) {
            null
        }

    /**
     * A linear pre-scan prevents excessive strings, nesting, and collections
     * from reaching org.json. Malformed inputs that stay inside the limits are
     * still handed to the parser so fuzzing exercises its error paths.
     */
    private fun withinResourceBounds(plaintext: String): Boolean =
        !utf8LengthExceedsLimit(plaintext) &&
            JsonStructureBoundsScanner().scan(plaintext)

    private fun utf8LengthExceedsLimit(plaintext: String): Boolean {
        var bytes = 0
        var index = 0
        while (index < plaintext.length) {
            val character = plaintext[index]
            bytes +=
                when {
                    character.code <= ASCII_MAX -> SINGLE_BYTE_UTF8_BYTES
                    character.code <= TWO_BYTE_CODE_POINT_MAX -> TWO_BYTE_UTF8_BYTES
                    character.isHighSurrogate() &&
                        index + 1 < plaintext.length &&
                        plaintext[index + 1].isLowSurrogate() -> {
                        index++
                        SURROGATE_PAIR_UTF8_BYTES
                    }
                    else -> OTHER_UTF8_BYTES
                }
            if (bytes > MAX_INPUT_BYTES) return true
            index++
        }
        return false
    }

    private class JsonStructureBoundsScanner {
        private val membersByDepth = IntArray(MAX_JSON_DEPTH + 1)
        private var depth = 0
        private var quote: Char? = null
        private var escaped = false

        fun scan(plaintext: String): Boolean {
            plaintext.forEach { character ->
                if (!consume(character)) return false
            }
            return true
        }

        private fun consume(character: Char): Boolean {
            val activeQuote = quote
            if (activeQuote != null) {
                consumeQuoted(character, activeQuote)
                return true
            }
            return when (character) {
                '\'', '"' -> startQuote(character)
                '{', '[' -> startContainer()
                '}', ']' -> endContainer()
                ',', ';' -> consumeSeparator()
                else -> true
            }
        }

        private fun consumeQuoted(
            character: Char,
            activeQuote: Char,
        ) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == activeQuote -> quote = null
            }
        }

        private fun startQuote(character: Char): Boolean {
            quote = character
            return true
        }

        private fun startContainer(): Boolean {
            depth++
            if (depth <= MAX_JSON_DEPTH) {
                membersByDepth[depth] = 1
            }
            return depth <= MAX_JSON_DEPTH
        }

        private fun endContainer(): Boolean {
            if (depth > 0) {
                membersByDepth[depth] = 0
                depth--
            }
            return true
        }

        private fun consumeSeparator(): Boolean {
            if (depth > 0) membersByDepth[depth]++
            return depth == 0 || membersByDepth[depth] <= MAX_COLLECTION_ELEMENTS
        }
    }

    private const val ASCII_MAX = 0x7F
    private const val TWO_BYTE_CODE_POINT_MAX = 0x7FF
    private const val SINGLE_BYTE_UTF8_BYTES = 1
    private const val TWO_BYTE_UTF8_BYTES = 2
    private const val OTHER_UTF8_BYTES = 3
    private const val SURROGATE_PAIR_UTF8_BYTES = 4
}
