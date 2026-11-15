package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountKeyPackageRelayEventFfi
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
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

/** Deletes a KeyPackage only for its still-active account through safe MDK-provided sources. */
suspend fun WhiteNoiseAppState.deleteKeyPackage(
    accountRef: String,
    eventIdHex: String,
    sourceRelays: List<String>,
): Boolean =
    deleteKeyPackageWithDependencies(
        accountRef = accountRef,
        eventIdHex = eventIdHex,
        sourceRelays = sourceRelays,
        classify = { relays -> marmotIo { classifyRelayEndpoints(relays) } },
        resolve = { host -> resolveKeyPackageDeletionHost(host) },
        delete = { account, eventId, relays ->
            marmotIo { deleteAccountKeyPackage(account, eventId, relays) }
        },
    )

/** Runs the account-fenced deletion route with injectable native and DNS boundaries. */
internal suspend fun WhiteNoiseAppState.deleteKeyPackageWithDependencies(
    accountRef: String,
    eventIdHex: String,
    sourceRelays: List<String>,
    classify: suspend (List<String>) -> List<RelayEndpointClassificationFfi>,
    resolve: KeyPackageDeletionHostResolver,
    delete: suspend (accountRef: String, eventIdHex: String, relays: List<String>) -> Unit,
): Boolean {
    val account = activeAccountRef?.takeIf { it == accountRef }
    val accountSwitchGeneration = account?.let(accountSession.handoff::captureForAccount)
    if (account == null || accountSwitchGeneration == null) return false
    val result =
        deleteKeyPackageThroughSafeSourceRelays(
            sourceRelays = sourceRelays,
            classify = classify,
            resolve = resolve,
            accountStillActive = {
                activeAccountRef == account && accountSession.handoff.isCurrent(accountSwitchGeneration)
            },
            delete = { relays -> delete(account, eventIdHex, relays) },
        )
    return presentKeyPackageDeletionResult(result, AppText.Resource(R.string.error_couldnt_verify_relay_hosts))
}

suspend fun WhiteNoiseAppState.publishNewKeyPackage(): Boolean {
    val account = activeAccountRef ?: return false
    return runCatching {
        marmotIo { publishNewKeyPackage(account) }
        presentTransient(R.string.toast_new_key_package_published)
        true
    }.getOrElse {
        if (it is CancellationException) throw it
        presentFailure(R.string.toast_couldnt_publish_key_package, "KEY_PACKAGE_PUBLISH", it)
        false
    }
}

suspend fun WhiteNoiseAppState.republishKeyPackage(): Boolean {
    val account = activeAccountRef ?: return false
    return runCatching {
        marmotIo { republishKeyPackage(account) }
        presentTransient(R.string.toast_key_package_republished)
        true
    }.getOrElse {
        if (it is CancellationException) throw it
        presentFailure(R.string.toast_couldnt_republish_key_package, "KEY_PACKAGE_REPUBLISH", it)
        false
    }
}
