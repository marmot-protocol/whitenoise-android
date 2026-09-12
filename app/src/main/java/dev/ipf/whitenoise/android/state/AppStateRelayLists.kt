package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R

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
        else -> publishMissingAccountRelayKinds(current) { kind, plan -> publishAccountRelays(account, kind, plan) }
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

/**
 * Publishes only missing kinds through the caller's validated setter. The all-lists native publisher would
 * overwrite an existing custom list with defaults. The caller owns the account's relay-operation guard.
 */
internal suspend fun publishMissingAccountRelayKinds(
    current: AccountRelayListsFfi,
    publish: suspend (RelayListKind, RelayListEditPlan) -> AccountRelayListsFfi?,
): AccountRelayListsFfi? {
    var updated = current
    val plan = RelayListEditPlan(normalizeRelayUrls(current.defaultRelays))
    for (missing in current.missing.distinct()) {
        if (missing !in updated.missing) continue
        val kind =
            when (missing) {
                MissingRelayListKindFfi.NIP65 -> RelayListKind.Nip65
                MissingRelayListKindFfi.INBOX -> RelayListKind.Inbox
            }
        updated = publish(kind, plan) ?: return null
    }
    return updated
}
