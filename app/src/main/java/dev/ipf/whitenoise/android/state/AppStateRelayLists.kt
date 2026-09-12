package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient

/** Publishes whichever relay lists the account is missing, seeded from MarmotKit's defaults. */
internal suspend fun WhiteNoiseAppState.publishMissingRelayLists(account: String?): AccountRelayListsFfi? {
    val current = account?.let { loadAccountRelayLists(it) }
    return when {
        account == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_active_account_period)
            null
        }
        current == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_relay_projection)
            null
        }
        current.complete -> current
        else ->
            runCatchingCancellable {
                marmotIo { publishRelayLists(account, current.defaultRelays, MarmotClient.bootstrapRelays) }
                loadAccountRelayLists(account)
            }.onSuccess {
                presentTransient(R.string.toast_relay_list_updated)
            }.onFailure {
                presentFailure(R.string.toast_relay_update_failed, "RELAY_LIST_PUBLISH", it)
            }.getOrNull()
    }
}

/** Replaces both relay lists with MarmotKit's defaults through the same validation an edit passes. */
internal suspend fun WhiteNoiseAppState.restoreDefaultAccountRelays(account: String?): AccountRelayListsFfi? {
    val current = account?.let { loadAccountRelayLists(it) }
    return when {
        account == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_active_account_period)
            null
        }
        current == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_relay_projection)
            null
        }
        else -> {
            val plan = RelayListEditPlan(normalizeRelayUrls(current.defaultRelays))
            publishAccountRelays(account, RelayListKind.Nip65, plan)
                ?.let { publishAccountRelays(account, RelayListKind.Inbox, plan) }
        }
    }
}
