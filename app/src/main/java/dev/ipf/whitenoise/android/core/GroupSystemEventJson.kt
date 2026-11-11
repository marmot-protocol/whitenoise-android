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
    /**
     * Labels MarmotKit prepared for this row's actor and subject (0.10.1). They are a fallback for a
     * reader that has no local name: an account-scoped nickname still wins, because the app owns its own
     * naming. Absent on rows parsed from JSON, where no identity is authenticated.
     */
    val actorDisplayName: String? = null,
    val subjectDisplayName: String? = null,
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
     * from reaching org.json. Lenient syntax that could hide brackets from the
     * count is rejected here; other malformed inputs that stay inside the
     * limits still reach the parser so fuzzing exercises its error paths.
     */
    internal fun withinResourceBounds(plaintext: String): Boolean =
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

    /**
     * Rejects the lenient syntax that could make the counted structure differ
     * from the structure org.json parses.
     *
     * Android's JSONTokener also accepts comments, single-quoted strings,
     * `;` and `=` separators and unquoted literals that may contain quotes.
     * Each of those can hide brackets from a counting scanner, so they are
     * rejected rather than modelled: kind-1210 content is produced by a
     * strict JSON serializer. Unquoted literals without quotes still pass,
     * because they end at the first structural character and cannot hide one.
     */
    private class JsonStructureBoundsScanner {
        private val membersByDepth = IntArray(MAX_JSON_DEPTH + 1)
        private var depth = 0
        private var inString = false
        private var escaped = false
        private var afterLiteral = false

        fun scan(plaintext: String): Boolean {
            plaintext.forEach { character ->
                if (!consume(character)) return false
            }
            return true
        }

        private fun consume(character: Char): Boolean {
            if (inString) {
                consumeQuoted(character)
                return true
            }
            val previousWasLiteral = afterLiteral
            afterLiteral = isLiteralCharacter(character)
            return when (character) {
                '"' -> startString(previousWasLiteral)
                '{', '[' -> startContainer()
                '}', ']' -> endContainer()
                ',' -> consumeSeparator()
                ':', ' ', '\t', '\n', '\r' -> true
                else -> afterLiteral
            }
        }

        private fun consumeQuoted(character: Char) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        }

        /** A quote glued to a literal is part of that literal on Android, not a string. */
        private fun startString(previousWasLiteral: Boolean): Boolean {
            inString = true
            return !previousWasLiteral
        }

        private fun startContainer(): Boolean {
            depth++
            if (depth <= MAX_JSON_DEPTH) {
                membersByDepth[depth] = 1
            }
            return depth <= MAX_JSON_DEPTH
        }

        private fun endContainer(): Boolean {
            if (depth == 0) return false
            membersByDepth[depth] = 0
            depth--
            return true
        }

        private fun consumeSeparator(): Boolean {
            if (depth > 0) membersByDepth[depth]++
            return depth == 0 || membersByDepth[depth] <= MAX_COLLECTION_ELEMENTS
        }

        private fun isLiteralCharacter(character: Char): Boolean =
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '+' ||
                character == '-' ||
                character == '.'
    }

    private const val ASCII_MAX = 0x7F
    private const val TWO_BYTE_CODE_POINT_MAX = 0x7FF
    private const val SINGLE_BYTE_UTF8_BYTES = 1
    private const val TWO_BYTE_UTF8_BYTES = 2
    private const val OTHER_UTF8_BYTES = 3
    private const val SURROGATE_PAIR_UTF8_BYTES = 4
}
