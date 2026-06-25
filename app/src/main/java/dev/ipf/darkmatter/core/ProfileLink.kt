package dev.ipf.darkmatter.core

import dev.ipf.darkmatter.BuildConfig
import java.net.URI

data class ProfileLink(
    val npub: String,
) {
    val uri: String = buildUri(npub)

    companion object {
        // A Nostr npub is bech32-encoded: prefix `npub1`, body in the bech32
        // alphabet (lowercase a-z 0-9 minus 'b' 'i' 'o' '1'), total length 63.
        // We don't bech32-decode here (that's the FFI's job) — we just reject
        // clearly-invalid inputs so ProfileSheet doesn't open against garbage.
        private const val NPUB_LENGTH = 63
        private val NPUB_BODY_CHARSET = Regex("^[ac-hj-np-z02-9]+$")

        internal fun buildUri(
            npub: String,
            appLinkBaseUrl: String = BuildConfig.WHITENOISE_PROFILE_LINK_BASE_URL,
            customScheme: String = BuildConfig.WHITENOISE_DEEP_LINK_SCHEME,
        ): String {
            val normalizedBase = appLinkBaseUrl.trim().trimEnd('/')
            return if (normalizedBase.isNotEmpty()) {
                "$normalizedBase/$npub"
            } else {
                "$customScheme://profile/$npub"
            }
        }

        private fun isLikelyNpub(value: String): Boolean {
            if (value.length != NPUB_LENGTH) return false
            if (!value.startsWith("npub1")) return false
            return NPUB_BODY_CHARSET.matches(value.substring(5))
        }

        fun parse(raw: String): ProfileLink? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            parseUri(trimmed)?.let { return it }

            if (trimmed.lowercase().startsWith("nostr:")) {
                val rest = trimmed.drop("nostr:".length)
                if (isLikelyNpub(rest)) return ProfileLink(rest)
            }
            if (isLikelyNpub(trimmed)) return ProfileLink(trimmed)
            return null
        }

        private fun parseUri(raw: String): ProfileLink? {
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() == "https") return parseAppLink(uri)
            if (uri.scheme?.lowercase() !in PROFILE_SCHEMES) return null

            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty().trim('/')
            return when {
                host.equals("profile", ignoreCase = true) && isLikelyNpub(path) -> ProfileLink(path)
                isLikelyNpub(host) -> ProfileLink(host)
                else -> null
            }
        }

        private fun parseAppLink(uri: URI): ProfileLink? {
            if (uri.rawUserInfo != null || uri.port != -1) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.host !in PROFILE_APP_LINK_HOSTS) return null

            val path = uri.rawPath.orEmpty()
            if (!path.startsWith(PROFILE_APP_LINK_PATH_PREFIX)) return null

            val npub = path.removePrefix(PROFILE_APP_LINK_PATH_PREFIX)
            if (npub.contains('/')) return null
            return if (isLikelyNpub(npub)) ProfileLink(npub) else null
        }

        // `darkmatter` is parse-only legacy support. The Android manifest no longer
        // registers that custom scheme for inbound intents.
        private val PROFILE_SCHEMES = setOf("whitenoise", "whitenoise-staging", "whitenoise-dev", "darkmatter")
        private val PROFILE_APP_LINK_HOSTS = setOf("whitenoise.chat", "www.whitenoise.chat")
        private const val PROFILE_APP_LINK_PATH_PREFIX = "/profile/"
    }
}
