package dev.ipf.darkmatter.core

object RecipientReference {
    private val hexPublicKey = Regex("^[0-9a-fA-F]{64}$")
    private val separator = Regex("[,\\s]+")

    fun tokenize(raw: String): List<String> =
        raw
            .split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        ProfileLink.parse(trimmed)?.let { return it.npub }
        if (hexPublicKey.matches(trimmed)) return trimmed.lowercase()
        return null
    }

    fun plausibleClipboardInput(
        raw: String?,
        allowHexPublicKey: Boolean = true,
    ): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        normalize(trimmed)?.let { normalized ->
            if (allowHexPublicKey || !hexPublicKey.matches(normalized)) return normalized
        }

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null
        val normalizedTokens =
            tokens.map { token ->
                normalize(token) ?: return null
            }
        if (!allowHexPublicKey && normalizedTokens.any { hexPublicKey.matches(it) }) return null
        return trimmed
    }
}
