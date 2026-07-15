package dev.ipf.whitenoise.android.core

import java.net.URI

enum class StickerInputKind {
    Pack,
    SignalImport,
}

data class StickerInput(
    val value: String,
    val kind: StickerInputKind,
)

/**
 * Classifies untrusted share/deep-link input without interpreting protocol
 * metadata. Native Marmot performs canonical coordinate, signature, and pack
 * validation; this gate only decides whether Android should route a value to
 * the sticker surface instead of the profile-link surface.
 */
object StickerLinks {
    private const val MaxInputChars = 2048

    fun classify(raw: String?): StickerInput? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MaxInputChars } ?: return null
        if (isSignalLink(value)) return StickerInput(value, StickerInputKind.SignalImport)
        if (isPackInput(value)) return StickerInput(value, StickerInputKind.Pack)
        return null
    }

    private fun isSignalLink(value: String): Boolean {
        val uri = parseUri(value) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return false
        if (!uri.host.equals("signal.art", ignoreCase = true)) return false
        if (uri.path?.trimEnd('/') != "/addstickers") return false
        val parameters = parameterNames(uri.rawFragment ?: uri.rawQuery ?: return false)
        return "pack_id" in parameters && "pack_key" in parameters
    }

    private fun isPackInput(value: String): Boolean {
        if (value.startsWith("30031:") || value.startsWith("naddr1") || value.startsWith("nostr:naddr1")) {
            return true
        }
        val uri = parseUri(value) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) return false
        if (uri.host?.lowercase() !in setOf("sonarprivacy.xyz", "www.sonarprivacy.xyz")) return false
        if (uri.path?.trimEnd('/') != "/stickers") return false
        return "a" in parameterNames(uri.rawQuery.orEmpty())
    }

    private fun parameterNames(encoded: String): Set<String> =
        encoded
            .split('&')
            .asSequence()
            .map { it.substringBefore('=') }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()
}
