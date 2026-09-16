package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountKeyPackageRelayEventFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.CancellationException

/** Every observed KeyPackage relay event for one account, current and superseded. */
internal typealias KeyPackageRelayEvents = List<AccountKeyPackageRelayEventFfi>

/**
 * Current and superseded KeyPackage relay events for the active account (MDK 0.10.0). Unlike
 * `accountKeyPackages`, which lists slot winners only, this shows every observed relay event so a stale
 * package left on a relay is visible; `isCurrent` marks the winner.
 */
internal suspend fun WhiteNoiseAppState.keyPackageRelayHistory(fromNetwork: Boolean = false): KeyPackageRelayEvents {
    val account = activeAccountRef ?: return emptyList()
    return runCatching {
        val bootstrapRelays = if (fromNetwork) MarmotClient.bootstrapRelays else emptyList()
        marmotIo { accountKeyPackageRelayEvents(account, bootstrapRelays) }
    }.getOrElse {
        if (it is CancellationException) throw it
        presentFailure(R.string.toast_couldnt_load_key_packages, "KEY_PACKAGE_HISTORY_LOAD", it)
        emptyList()
    }
}
