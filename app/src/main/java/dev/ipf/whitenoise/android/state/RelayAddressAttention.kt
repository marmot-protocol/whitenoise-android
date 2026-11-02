package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.HostSafety
import java.net.URI
import java.util.Locale

/**
 * Why a relay address shown in settings needs attention, for presentation and add-sheet feedback.
 *
 * MarmotKit remains the dial and publish boundary: its `classifyRelayEndpoints` reports every onion endpoint as
 * `UNSAFE`, the same value it uses for plaintext clearnet and private hosts, so it cannot tell these apart yet
 * (mdk#2588). This split chooses wording, and it never admits an address MarmotKit would refuse.
 */
internal enum class RelayAddressAttention {
    /** A secure public `wss://` address the editor accepts. */
    None,

    /** An ordinary clearnet host over unencrypted `ws://`. */
    InsecureTransport,

    /** An onion-service host, reachable only through Tor, which White Noise does not route through. */
    OnionRequiresTor,

    /** Malformed, unsupported or unsafe: an address White Noise can't use, whatever the reason. */
    Unusable,
}

/**
 * Classifies by the parsed scheme and host, never by substrings: `.onion` inside a path, query, username or a
 * suffix such as `.onion.example` does not make an address an onion relay.
 *
 * Stopgap: the onion branch mirrors MDK's own TorRequired rule — the parsed domain, trailing dot trimmed, ends in
 * `.onion` — because MarmotKit folds that reason into `UNSAFE`. It applies to `ws://` too, as MDK onboarding keeps
 * both schemes as Tor declarations. Delete it and read the reason from MarmotKit once mdk#2588 exposes it.
 */
internal fun relayAddressAttention(url: String): RelayAddressAttention {
    val trimmed = url.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull()
    val scheme = uri?.scheme?.lowercase(Locale.ROOT)
    val host =
        uri
            ?.takeIf { it.userInfo == null && scheme in RELAY_SCHEMES }
            ?.host
            ?.lowercase(Locale.ROOT)
            ?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() }
    return when {
        host != null && isOnionHost(host) -> RelayAddressAttention.OnionRequiresTor
        relayUrlValidationResult(trimmed) == RelayUrlValidationResult.Acceptable -> RelayAddressAttention.None
        scheme == "ws" && host != null && !HostSafety.isPrivateOrLoopbackHost(host) ->
            RelayAddressAttention.InsecureTransport
        else -> RelayAddressAttention.Unusable
    }
}

/** MDK's onion rule (`ends_with(".onion")` on the dot-trimmed domain); a bare `onion` host is not one. */
internal fun isOnionHost(host: String): Boolean = host.lowercase(Locale.ROOT).trimEnd('.').endsWith(ONION_SUFFIX)

private val RELAY_SCHEMES = setOf("ws", "wss")
private const val ONION_SUFFIX = ".onion"
