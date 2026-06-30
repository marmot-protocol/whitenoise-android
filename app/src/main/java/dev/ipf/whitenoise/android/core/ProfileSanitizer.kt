package dev.ipf.whitenoise.android.core

import java.net.URI
import java.text.Normalizer

object ProfileSanitizer {
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_ABOUT_LENGTH = 1000
    private const val MAX_MESSAGE_LENGTH = 8000
    private val blankLineRun = Regex("\n{3,}")
    private val whitespaceRun = Regex("\\s+")

    fun displayName(raw: String?): String? = singleLine(raw, MAX_NAME_LENGTH)

    fun about(raw: String?): String? = multiline(raw, MAX_ABOUT_LENGTH)

    fun messageBody(raw: String): String {
        val clamped = stripUnsafe(raw).replace(blankLineRun, "\n\n").trim()
        return safeTake(clamped, MAX_MESSAGE_LENGTH)
    }

    /**
     * The single avatar-URL sanitizer for the whole app (profile records, image
     * search results, the avatar preview);
     * [dev.ipf.whitenoise.android.media.sanitizeHttpsAvatarUrl] delegates here so
     * the two can't drift on policy. Returns the sanitized https URL string, or
     * null when it isn't an avatar-safe HTTPS URL.
     */
    fun imageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var candidate = raw.trim()
        // Upgrade a scheme-relative `//host/path` to https so a record that omits
        // the scheme still resolves to a safe absolute URL.
        if (candidate.startsWith("//")) candidate = "https:$candidate"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host
        if (host.isNullOrBlank()) return null
        // Reject embedded credentials (`https://user:pass@host`): they can leak
        // to the host and let `user@` mask the real authority.
        if (!uri.rawUserInfo.isNullOrEmpty()) return null
        // SSRF guard: never let an avatar URL point the app at loopback or the
        // local network. See issue #89.
        if (HostSafety.isPrivateOrLoopbackHost(host)) return null
        return candidate
    }

    private fun singleLine(
        raw: String?,
        maxLength: Int,
    ): String? {
        val collapsed =
            stripUnsafe(normalizeCompatibilityForms(raw ?: ""))
                .split(whitespaceRun)
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
            value.codePoints().forEach { cp ->
                val type = Character.getType(cp)
                when {
                    cp == '\n'.code || cp == '\t'.code || cp == '\r'.code -> appendCodePoint(cp)
                    type == Character.CONTROL.toInt() -> Unit
                    type == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D -> Unit
                    cp in 0xE0000..0xE007F -> Unit // supplementary TAG characters
                    cp in 0xE0100..0xE01EF -> Unit // supplementary variation selectors
                    cp == 0x200E || cp == 0x200F -> Unit
                    cp in 0x202A..0x202E -> Unit
                    cp in 0x2066..0x2069 -> Unit
                    cp == 0x061C -> Unit
                    cp == 0x200B || cp == 0xFEFF -> Unit
                    // More invisible/default-ignorable format chars abused for
                    // spoofing. ZWNJ (0x200C) and ZWJ (0x200D) are kept — they
                    // carry meaning in Indic/Arabic shaping and emoji sequences.
                    cp == 0x00AD -> Unit // soft hyphen
                    cp == 0x034F -> Unit // combining grapheme joiner
                    cp == 0x180E -> Unit // Mongolian vowel separator: default-ignorable since Unicode 6.3
                    cp == 0x2060 -> Unit // word joiner
                    cp in 0x2061..0x2064 -> Unit // invisible math operators
                    else -> appendCodePoint(cp)
                }
            }
        }
}
