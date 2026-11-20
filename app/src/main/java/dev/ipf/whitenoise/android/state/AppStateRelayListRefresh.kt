package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.core.MarmotClient

/**
 * Fetches the account's published relay lists instead of trusting the cache, so a list published from another client
 * shows up before the user decides to publish. MarmotKit merges the answer into the cache the screen reads, and an
 * empty or older answer never clears a list it already knows. Null when the fetch fails, so the caller keeps the
 * last known status.
 */
internal suspend fun WhiteNoiseAppState.refreshAccountRelayLists(account: String): AccountRelayListsFfi? {
    val accountIdHex = accounts.firstOrNull { it.label == account }?.accountIdHex ?: return null
    return runCatchingCancellable {
        marmotIo {
            val cachedNip65 = runCatchingCancellable { accountRelayLists(account).nip65.relays }.getOrNull()
            refreshUserRelayLists(accountIdHex, relayListRefreshRelays(cachedNip65.orEmpty()))
        }
    }.getOrNull()
}

/**
 * Where a refresh looks for the account's lists: the discovery relays sign-in uses, then the cached [nip65] relays the
 * account publishes to, each once. The two White Noise relays alone would miss a list published only elsewhere. Only
 * reads go to these relays, so MarmotKit's dial policy decides which are reachable, not the app's publish checks.
 */
internal fun relayListRefreshRelays(nip65: List<String>) = (MarmotClient.discoveryRelays + nip65).distinct()
