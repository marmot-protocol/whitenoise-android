package dev.ipf.darkmatter.core

import java.net.URI
import java.text.Normalizer

object ProfileSanitizer {
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_ABOUT_LENGTH = 1000
    private const val MAX_MESSAGE_LENGTH = 8000

    fun displayName(raw: String?): String? = singleLine(raw, MAX_NAME_LENGTH)

    fun about(raw: String?): String? = multiline(raw, MAX_ABOUT_LENGTH)

    fun messageBody(raw: String): String {
        val clamped = stripUnsafe(raw).replace(Regex("\n{3,}"), "\n\n").trim()
        return safeTake(clamped, MAX_MESSAGE_LENGTH)
    }

    fun imageUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        if (uri.host.isNullOrBlank()) return null
        // SSRF guard: never let an avatar URL point the app at loopback or the
        // local network. See issue #89.
        if (HostSafety.isPrivateOrLoopbackHost(uri.host)) return null
        return uri.toString()
    }

    private fun singleLine(
        raw: String?,
        maxLength: Int,
    ): String? {
        val collapsed =
            stripUnsafe(normalizeCompatibilityForms(raw ?: ""))
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        return collapsed.takeIf { it.isNotEmpty() }?.let { safeTake(it, maxLength) }
    }

    /**
     * NFKC folds compatibility/fullwidth look-alikes to their canonical forms
     * (`ＡＢＣ` → `ABC`, `ﬁ` → `fi`), removing a large class of display-name
     * homoglyphs. Applied to the display-name surface only, not message
     * bodies, which must keep the author's exact text. This is compatibility
     * normalization, not full confusables mapping: it does not fold
     * cross-script look-alikes (Cyrillic `а` vs Latin `a`) — that needs the
     * Unicode confusables skeleton table and a mixed-script policy.
     */
    private fun normalizeCompatibilityForms(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)

    private fun multiline(
        raw: String?,
        maxLength: Int,
    ): String? {
        val cleaned = stripUnsafe(raw ?: "").trim()
        return cleaned.takeIf { it.isNotEmpty() }?.let { safeTake(it, maxLength) }
    }

    // `String.take(n)` counts UTF-16 code units, so a cap of 80 silently
    // becomes 40 for an emoji-heavy name and can also split a surrogate pair
    // at the boundary. Truncate by code points instead so MAX_NAME_LENGTH and
    // friends mean the same number of grapheme bases regardless of plane.
    private fun safeTake(
        value: String,
        maxLength: Int,
    ): String {
        if (value.codePointCount(0, value.length) <= maxLength) return value
        val end = value.offsetByCodePoints(0, maxLength)
        return value.substring(0, end)
    }

    fun stripUnsafe(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                when {
                    char == '\n' || char == '\t' || char == '\r' -> append(char)
                    Character.getType(char) == Character.CONTROL.toInt() -> Unit
                    char.code == 0x200E || char.code == 0x200F -> Unit
                    char.code in 0x202A..0x202E -> Unit
                    char.code in 0x2066..0x2069 -> Unit
                    char.code == 0x061C -> Unit
                    char.code == 0x200B || char.code == 0xFEFF -> Unit
                    // More invisible/default-ignorable format chars abused for
                    // spoofing. ZWNJ (0x200C) and ZWJ (0x200D) are kept — they
                    // carry meaning in Indic/Arabic shaping and emoji sequences.
                    char.code == 0x00AD -> Unit // soft hyphen
                    char.code == 0x034F -> Unit // combining grapheme joiner
                    char.code == 0x180E -> Unit // Mongolian vowel separator: default-ignorable since Unicode 6.3
                    char.code == 0x2060 -> Unit // word joiner
                    char.code in 0x2061..0x2064 -> Unit // invisible math operators
                    else -> append(char)
                }
            }
        }
}
