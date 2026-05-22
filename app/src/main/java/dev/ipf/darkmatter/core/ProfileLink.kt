package dev.ipf.darkmatter.core

import java.net.URI

data class ProfileLink(val npub: String) {
    val uri: String = "darkmatter://profile/$npub"

    companion object {
        fun parse(raw: String): ProfileLink? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            parseUri(trimmed)?.let { return it }

            if (trimmed.lowercase().startsWith("nostr:")) {
                val rest = trimmed.drop("nostr:".length)
                if (rest.startsWith("npub")) return ProfileLink(rest)
            }
            if (trimmed.startsWith("npub")) return ProfileLink(trimmed)
            return null
        }

        private fun parseUri(raw: String): ProfileLink? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "darkmatter") return null

            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty().trim('/')
            return when {
                host.equals("profile", ignoreCase = true) && path.startsWith("npub") -> ProfileLink(path)
                host.startsWith("npub") -> ProfileLink(host)
                else -> null
            }
        }
    }
}
