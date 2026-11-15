package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

suspend fun WhiteNoiseAppState.accountRelayLists(): AccountRelayListsFfi? = activeAccountRef?.let { loadAccountRelayLists(it) }

/** Adds a relay while preserving the other declared addresses for this list. */
suspend fun WhiteNoiseAppState.addAccountRelay(
    account: String?,
    kind: RelayListKind,
    relay: String,
): AccountRelayListsFfi? {
    val validationError =
        when (relayUrlValidationResult(relay)) {
            RelayUrlValidationResult.Acceptable -> null
            RelayUrlValidationResult.Invalid -> R.string.error_invalid_relay_url
        }
    val current = account?.takeIf { validationError == null }?.let { loadRelayEditAddresses(it, kind) }
    val plan = current?.let { relayListAfterAddition(it.relays, relay)?.copy(readOnlyRelays = it.readOnly) }
    return when {
        validationError != null -> {
            present(R.string.toast_relay_update_failed, validationError)
            null
        }
        account == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_active_account_period)
            null
        }
        current == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_relay_projection)
            null
        }
        plan == null -> {
            present(R.string.toast_relay_update_failed, R.string.error_invalid_relay_url)
            null
        }
        else -> publishAccountRelays(account, kind, plan)
    }
}

/** Removes a relay without truncating the list to its routing projection. */
suspend fun WhiteNoiseAppState.removeAccountRelay(
    account: String?,
    kind: RelayListKind,
    relay: String,
): AccountRelayListsFfi? {
    val current = account?.let { loadRelayEditAddresses(it, kind) }
    return when {
        account == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_active_account_period)
            null
        }
        current == null -> {
            present(R.string.toast_relay_update_failed, R.string.no_relay_projection)
            null
        }
        else ->
            publishAccountRelays(
                account,
                kind,
                relayListAfterRemoval(current.relays, relay).copy(readOnlyRelays = current.readOnly),
            )
    }
}

/** Validates and publishes one relay-list edit after app-level and MarmotKit policy checks. */
internal suspend fun WhiteNoiseAppState.publishAccountRelays(
    account: String,
    kind: RelayListKind,
    plan: RelayListEditPlan,
): AccountRelayListsFfi? =
    when (val validation = relayPublishValidation(plan)) {
        is RelayPublishValidation.Rejected -> {
            when (validation.error) {
                RelayPublishValidationError.Invalid ->
                    present(R.string.toast_relay_update_failed, R.string.error_invalid_relay_url)
                RelayPublishValidationError.Blocked ->
                    present(
                        R.string.toast_relay_update_failed,
                        R.string.error_remove_invalid_relay_urls_first,
                    )
                RelayPublishValidationError.Unavailable ->
                    present(
                        R.string.toast_relay_update_failed,
                        R.string.error_couldnt_verify_relay_hosts,
                    )
            }
            null
        }

        is RelayPublishValidation.Ready ->
            runCatchingCancellable {
                marmotIo {
                    when (kind) {
                        RelayListKind.Nip65 ->
                            setAccountNip65Relays(account, validation.relays, MarmotClient.bootstrapRelays)
                        RelayListKind.Inbox ->
                            setAccountInboxRelays(account, validation.relays, MarmotClient.bootstrapRelays)
                    }
                }
            }.onSuccess {
                presentTransient(R.string.toast_relay_list_updated)
            }.onFailure {
                presentFailure(R.string.toast_relay_update_failed, "RELAY_LIST_UPDATE", it)
            }.getOrNull()
    }

internal suspend fun WhiteNoiseAppState.loadAccountRelayLists(account: String): AccountRelayListsFfi? =
    runCatchingCancellable { marmotIo { accountRelayLists(account) } }.getOrNull()

/** Applies MarmotKit's shared relay policy and strips only unsafe pre-existing entries. */
private suspend fun WhiteNoiseAppState.relayPublishValidation(plan: RelayListEditPlan): RelayPublishValidation =
    if (plan.relays.isEmpty() || plan.relays.any { !isAcceptableRelayUrl(it) }) {
        RelayPublishValidation.Rejected(RelayPublishValidationError.Invalid)
    } else {
        when (withContext(Dispatchers.IO) { requiredRelayResolveTimeCheckResult(plan) }) {
            RelayResolveTimeCheckResult.Blocked ->
                RelayPublishValidation.Rejected(RelayPublishValidationError.Blocked)
            RelayResolveTimeCheckResult.Unavailable ->
                RelayPublishValidation.Rejected(RelayPublishValidationError.Unavailable)
            RelayResolveTimeCheckResult.Passed ->
                runCatchingCancellable {
                    marmotIo { classifyRelayEndpoints(plan.relays) }
                }.fold(
                    onSuccess = { classified ->
                        allowedRelayUrlsForPublish(plan, classified)
                            ?.let(RelayPublishValidation::Ready)
                            ?: RelayPublishValidation.Rejected(RelayPublishValidationError.Blocked)
                    },
                    onFailure = {
                        RelayPublishValidation.Rejected(RelayPublishValidationError.Unavailable)
                    },
                )
        }
    }

/**
 * Reads the addresses an edit starts from: the full NIP-65 list, so the native setter can preserve
 * read-only roles, or the Inbox list. A NIP-65 read also marks entries outside the posting projection
 * as read-only, and fails when either list is unavailable.
 */
private suspend fun WhiteNoiseAppState.loadRelayEditAddresses(
    account: String,
    kind: RelayListKind,
): RelayEditAddresses? =
    when (kind) {
        RelayListKind.Nip65 -> {
            val declared = runCatchingCancellable { marmotIo { accountNip65Relays(account) } }.getOrNull()
            val posting = loadAccountRelayLists(account)?.nip65?.relays
            if (declared == null || posting == null) {
                null
            } else {
                val readOnly = normalizeRelayUrls(declared).toSet() - normalizeRelayUrls(posting).toSet()
                RelayEditAddresses(declared, readOnly)
            }
        }
        RelayListKind.Inbox -> loadAccountRelayLists(account)?.inbox?.relays?.let { RelayEditAddresses(it) }
    }

fun WhiteNoiseAppState.bootstrapRelayCount(): Int = MarmotClient.bootstrapRelays.size
