package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Cancellation of a relay editor must preserve every identity's complete native relay projection. */
internal suspend fun verifyMaestroRelayLists(
    native: Marmot,
    state: WhiteNoiseAppState,
    baseline: MaestroRelayListsBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition != "relay-lists-unchanged") return false
    val before = checkNotNull(baseline)
    check(before.accounts.size == 3 && before.owner in before.accounts)
    check(withContext(Dispatchers.Main.immediate) { state.activeAccountRef == before.owner })
    return withTimeoutOrNull(15_000L) {
        withContext(Dispatchers.IO) {
            before.accounts.all { (account, lists) ->
                native.accountRelayLists(account) == lists
            }
        }
    } ?: false
}
