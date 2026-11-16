package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.whitenoise.android.core.HostSafety
import dev.ipf.whitenoise.android.core.MarmotClient
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

enum class RelayListKind {
    Nip65,
    Inbox,
}

internal enum class RelayUrlValidationResult {
    Acceptable,
    Invalid,
}

/**
 * One relay-list replacement. [readOnlyRelays] are canonical NIP-65 entries MarmotKit keeps read-only,
 * so at least one allowed relay outside them must survive for the account to keep a posting relay.
 */
internal data class RelayListEditPlan(
    val relays: List<String>,
    val requiredRelay: String? = null,
    val readOnlyRelays: Set<String> = emptySet(),
)

/** The declared addresses one relay edit starts from, with the canonical NIP-65 entries kept read-only. */
internal class RelayEditAddresses(
    val relays: List<String>,
    val readOnly: Set<String> = emptySet(),
)

internal enum class RelayPublishValidationError {
    Invalid,
    Blocked,
    Unavailable,
}

internal sealed interface RelayPublishValidation {
    data class Ready(
        val relays: List<String>,
    ) : RelayPublishValidation

    data class Rejected(
        val error: RelayPublishValidationError,
    ) : RelayPublishValidation
}

/** Canonicalizes secure public relay URLs without restricting them to White Noise-owned hosts. */
internal fun normalizeRelayUrls(relays: Iterable<String>): List<String> =
    relays
        .mapNotNull(::canonicalRelayUrl)
        .distinct()

/** Returns whether a relay URL passes the synchronous structural and literal-host checks. */
internal fun isAcceptableRelayUrl(url: String): Boolean = relayUrlValidationResult(url) == RelayUrlValidationResult.Acceptable

/** Classifies a relay URL for immediate editor feedback; MarmotKit remains the publish boundary. */
internal fun relayUrlValidationResult(url: String): RelayUrlValidationResult =
    if (canonicalRelayUrl(url) == null) RelayUrlValidationResult.Invalid else RelayUrlValidationResult.Acceptable

/** Builds an add mutation while preserving every other structurally valid imported relay. */
internal fun relayListAfterAddition(
    currentRelays: List<String>,
    relayToAdd: String,
): RelayListEditPlan? {
    val canonicalRelay = canonicalRelayUrl(relayToAdd) ?: return null
    return RelayListEditPlan(
        relays = normalizeRelayUrls(currentRelays + canonicalRelay),
        requiredRelay = canonicalRelay,
    )
}

/** Builds a removal mutation that deletes only the selected relay and keeps safe imported siblings. */
internal fun relayListAfterRemoval(
    currentRelays: List<String>,
    relayToRemove: String,
    fallbackRelays: List<String> = MarmotClient.bootstrapRelays,
): RelayListEditPlan {
    val target = relayToRemove.trim()
    val remaining =
        currentRelays
            .map(String::trim)
            .filter { it.isNotEmpty() && it != target }
            .distinct()
    val supported = normalizeRelayUrls(remaining)
    return RelayListEditPlan(
        relays = supported.ifEmpty { normalizeRelayUrls(fallbackRelays) },
    )
}

/** Prevents removing the final publishable relay while permitting targeted cleanup. */
internal fun canRemoveRelay(
    currentRelays: List<String>,
    relay: String,
): Boolean = normalizeRelayUrls(currentRelays.filterNot { it.trim() == relay.trim() }).isNotEmpty()

/**
 * Keeps MarmotKit-approved endpoints while requiring a requested addition to survive classification,
 * and refuses a list whose only approved entries are read-only, which would leave no posting relay.
 */
internal fun allowedRelayUrlsForPublish(
    plan: RelayListEditPlan,
    classified: List<RelayEndpointClassificationFfi>,
): List<String>? {
    val requiredRelayAllowed =
        plan.requiredRelay == null ||
            classified.any {
                it.policy == RelayEndpointPolicyFfi.ALLOWED && canonicalRelayUrl(it.endpoint) == plan.requiredRelay
            }
    val allowedRelays =
        classified
            .filter { it.policy == RelayEndpointPolicyFfi.ALLOWED }
            .mapNotNull(RelayEndpointClassificationFfi::normalizedEndpoint)
            .mapNotNull(::canonicalRelayUrl)
            .distinct()
    return allowedRelays.takeIf { relays -> requiredRelayAllowed && relays.any { it !in plan.readOnlyRelays } }
}

internal enum class RelayResolveTimeCheckResult {
    Passed,
    Blocked,
    Unavailable,
}

/** Injectable DNS resolver for relay resolve-time SSRF checks (unit tests). */
internal typealias RelayHostResolver = (String) -> Array<InetAddress>?

/**
 * Resolve-time SSRF guard for app-owned relay fetches. Call from a background
 * dispatcher after cheap [canonicalRelayUrl] / [normalizeRelayUrls] canonicalization.
 */
internal fun relayUrlResolveTimeCheckResult(
    canonicalUrl: String,
    resolve: RelayHostResolver = ::resolveRelayHost,
): RelayResolveTimeCheckResult {
    val host =
        runCatching { URI(canonicalUrl).host?.removeSurrounding("[", "]") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return RelayResolveTimeCheckResult.Blocked
    val resolved = resolve(host) ?: return RelayResolveTimeCheckResult.Unavailable
    if (resolved.isEmpty()) return RelayResolveTimeCheckResult.Unavailable
    return if (resolved.none { HostSafety.isPrivateOrLoopbackAddress(it) }) {
        RelayResolveTimeCheckResult.Passed
    } else {
        RelayResolveTimeCheckResult.Blocked
    }
}

internal fun relayUrlPassesResolveTimeCheck(
    canonicalUrl: String,
    resolve: RelayHostResolver = ::resolveRelayHost,
): Boolean = relayUrlResolveTimeCheckResult(canonicalUrl, resolve) == RelayResolveTimeCheckResult.Passed

/** Resolve-checks only a newly requested relay; removal plans preserve unreachable imported relays. */
internal fun requiredRelayResolveTimeCheckResult(
    plan: RelayListEditPlan,
    resolve: RelayHostResolver = ::resolveRelayHost,
): RelayResolveTimeCheckResult =
    plan.requiredRelay
        ?.let { relayUrlResolveTimeCheckResult(it, resolve) }
        ?: RelayResolveTimeCheckResult.Passed

private fun resolveRelayHost(host: String): Array<InetAddress>? = runCatching { InetAddress.getAllByName(host) }.getOrNull()

private fun canonicalRelayUrl(url: String): String? {
    return runCatching {
        val uri = URI(url.trim())
        if (uri.scheme?.equals("wss", ignoreCase = true) != true || uri.userInfo != null) {
            return@runCatching null
        }
        if (uri.port != -1 && uri.port != 443) return@runCatching null
        val host = uri.host ?: uri.rawAuthority?.relayHostCandidate() ?: return@runCatching null
        val hostWithoutBrackets = host.removeSurrounding("[", "]")
        if (hostWithoutBrackets.any { it.isWhitespace() }) return@runCatching null
        val asciiHost =
            if (hostWithoutBrackets.contains(":")) {
                hostWithoutBrackets
            } else {
                IDN.toASCII(hostWithoutBrackets)
            }
        val canonicalHost =
            asciiHost.lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
                ?: return@runCatching null
        // SSRF guard: relay URLs can arrive from untrusted protocol messages, so
        // never accept one that points the client at loopback or the local
        // network. See issue #82.
        if (HostSafety.isPrivateOrLoopbackHost(canonicalHost)) return@runCatching null
        val authorityHost = if (canonicalHost.contains(":")) "[$canonicalHost]" else canonicalHost
        val port =
            uri.port
                .takeIf { it >= 0 }
                ?.let { ":$it" }
                .orEmpty()
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "wss://$authorityHost$port$path$query"
    }.getOrNull()
}

private fun String.relayHostCandidate(): String? {
    if (isBlank() || contains("@")) return null
    if (startsWith("[")) return substringAfter("[", "").substringBefore("]", "").takeIf { it.isNotBlank() }
    return if (count { it == ':' } == 1) substringBefore(":") else this
}
