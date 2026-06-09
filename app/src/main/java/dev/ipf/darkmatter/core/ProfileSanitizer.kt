package dev.ipf.darkmatter.core

import java.net.URI

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
            stripUnsafe(raw ?: "")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        return collapsed.takeIf { it.isNotEmpty() }?.let { safeTake(it, maxLength) }
    }

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
                    else -> append(char)
                }
            }
        }
}
