package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.whitenoise.android.core.MarmotClient

/** MarmotKit refuses a relay fetch that names more endpoints than its per-route limit. */
private const val MAX_RELAYS_PER_REFRESH_FETCH = 16

/**
 * Fetches the account's published relay lists instead of trusting the cache, so a list published from another client
 * shows up before the user decides to publish. Each batch's answer is merged into the cache the screen reads, so the
 * last batch returns the status of them all, and an empty or older answer never clears a list MarmotKit already
 * knows. Null when a fetch fails, so the caller keeps the last known status.
 */
internal suspend fun WhiteNoiseAppState.refreshAccountRelayLists(account: String): AccountRelayListsFfi? {
    val accountIdHex = accounts.firstOrNull { it.label == account }?.accountIdHex ?: return null
    return runCatchingCancellable {
        marmotIo {
            val cachedNip65 = runCatchingCancellable { accountRelayLists(account).nip65.relays }.getOrNull()
            val candidates = MarmotClient.discoveryRelays + cachedNip65.orEmpty()
            relayListRefreshBatches(classifyRelayEndpoints(candidates))
                .map { batch -> refreshUserRelayLists(accountIdHex, batch) }
                .lastOrNull()
        }
    }.getOrNull()
}

/**
 * Where a refresh looks for the account's lists: the discovery relays sign-in uses, then the cached NIP-65 relays the
 * account publishes to, in the order [classified] lists them. Each endpoint MarmotKit allows goes once, in its
 * spelling, in batches it accepts. One it would refuse is left out rather than failing the whole fetch, and the app's
 * publish checks do not apply to these reads.
 */
internal fun relayListRefreshBatches(classified: List<RelayEndpointClassificationFfi>): List<List<String>> =
    classified
        .filter { it.policy == RelayEndpointPolicyFfi.ALLOWED }
        .mapNotNull { it.normalizedEndpoint }
        .distinct()
        .chunked(MAX_RELAYS_PER_REFRESH_FETCH)
