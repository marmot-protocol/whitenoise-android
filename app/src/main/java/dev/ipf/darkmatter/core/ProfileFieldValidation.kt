package dev.ipf.darkmatter.core

/**
 * Client-side format checks for the editable profile fields, so the edit
 * screen can flag malformed input and refuse to publish it rather than
 * pushing junk (or an SSRF-prone avatar URL) to relays. Every field is
 * optional, so a blank value is always acceptable. See issue #69.
 */
object ProfileFieldValidation {
    // Internet identifier: <local>@<domain-with-a-dot>, no whitespace or extra
    // '@'. Shared by NIP-05 and the lud16 Lightning address (same shape).
    private val INTERNET_IDENTIFIER = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** True when [raw] is blank or a sanitizable https avatar URL (see [ProfileSanitizer.imageUrl]). */
    fun isAcceptablePictureUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isEmpty() || ProfileSanitizer.imageUrl(trimmed) != null
    }

    /** True when [raw] is blank or a well-formed NIP-05 `user@domain` identifier. */
    fun isAcceptableNip05(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isEmpty() || INTERNET_IDENTIFIER.matches(trimmed)
    }

    /** True when [raw] is blank or a well-formed `user@domain` Lightning (lud16) address. */
    fun isAcceptableLud16(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isEmpty() || INTERNET_IDENTIFIER.matches(trimmed)
    }
}
