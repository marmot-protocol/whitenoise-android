package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Cancellation of a relay editor must preserve every identity's complete native relay projection. */
internal suspend fun verifyMaestroRelayLists(
    native: Marmot,
    state: WhiteNoiseAppState,
    baseline: Map<String, AccountRelayListsFfi>,
    postcondition: String?,
): Boolean {
    if (postcondition != "relay-lists-unchanged") return false
    check(baseline.size == 3)
    val owner = baseline.keys.first()
    check(withContext(Dispatchers.Main.immediate) { state.activeAccountRef == owner })
    withTimeout(15_000L) {
        withContext(Dispatchers.IO) {
            baseline.forEach { (account, lists) ->
                check(native.accountRelayLists(account) == lists) { "Relay editor changed an account's native lists" }
            }
        }
    }
    return true
}
