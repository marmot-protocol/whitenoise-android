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
}
