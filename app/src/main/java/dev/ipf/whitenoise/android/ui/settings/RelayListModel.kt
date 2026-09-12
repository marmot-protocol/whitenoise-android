package dev.ipf.whitenoise.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.RelayListKind
import dev.ipf.whitenoise.android.state.RelayUrlValidationResult
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.normalizeRelayUrls
import dev.ipf.whitenoise.android.state.relayUrlValidationResult
import java.util.WeakHashMap

/** Every relay in either list, in first-seen order, with the lists it serves. */
internal fun accountRelays(lists: AccountRelayListsFfi): List<AccountRelay> {
    val roles = linkedMapOf<String, MutableSet<AccountRelayRole>>()
    AccountRelayRole.entries.forEach { role ->
        lists.relaysFor(role.kind).forEach { url -> roles.getOrPut(url.trim()) { linkedSetOf() } += role }
    }
    return roles.map { (url, set) -> AccountRelay(url, set) }
}

internal fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> nip65.relays
        RelayListKind.Inbox -> inbox.relays
    }

/** True when both lists equal MarmotKit's default relays, so Restore default relays has nothing to do. */
internal fun AccountRelayListsFfi.usesDefaultRelays(): Boolean {
    val defaults = normalizeRelayUrls(defaultRelays).toSet()
    return normalizeRelayUrls(nip65.relays).toSet() == defaults && normalizeRelayUrls(inbox.relays).toSet() == defaults
}

/** Published, Missing, or Status unavailable while there is no projection to read. */
@StringRes
internal fun relayListStatusRes(
    lists: AccountRelayListsFfi?,
    kind: MissingRelayListKindFfi,
): Int =
    when {
        lists == null -> R.string.relay_list_status_unavailable
        kind in lists.missing -> R.string.relay_list_missing
        else -> R.string.relay_list_published
    }

/** The explainer beneath the relay lists, matching whichever status they are in. */
@StringRes
internal fun relayListsHelpRes(lists: AccountRelayListsFfi?): Int =
    when {
        lists == null -> R.string.relay_lists_unavailable_help
        lists.missing.isNotEmpty() -> R.string.relay_lists_missing_help
        else -> R.string.relay_lists_published_help
    }

/** One relay the account uses and the lists it appears in. */
internal data class AccountRelay(
    val url: String,
    val roles: Set<AccountRelayRole>,
) {
    /** The address without its scheme, the closest thing to a name a relay list carries. */
    val name: String get() = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

    /** An imported address that is not a secure wss:// URL; kept visible, flagged, never silently dropped. */
    val needsAttention: Boolean get() = relayUrlValidationResult(url) != RelayUrlValidationResult.Acceptable
}

/** The two lists MarmotKit publishes for an account, as the prototype's roles. */
internal enum class AccountRelayRole(
    val kind: RelayListKind,
    @param:StringRes val labelRes: Int,
    @param:StringRes val helpRes: Int,
) {
    Profile(RelayListKind.Nip65, R.string.profile, R.string.relay_role_profile_help),
    Inbox(RelayListKind.Inbox, R.string.relay_role_inbox, R.string.relay_role_inbox_help),
}

/** Refresh reloads the projection; PublishMissing asks MarmotKit to publish the lists it reports missing. */
internal enum class RelayPublicationOperation(
    @param:StringRes val progressRes: Int,
    @param:StringRes val failureRes: Int,
) {
    Refresh(R.string.relay_list_refreshing, R.string.relay_list_refresh_failed),
    PublishMissing(R.string.relay_list_publishing, R.string.relay_list_publish_failed),
}

/** Which publication operation is running or last failed; both null when idle. */
internal data class RelayPublicationState(
    val running: RelayPublicationOperation? = null,
    val failed: RelayPublicationOperation? = null,
)

/** Everything the list screen renders from. */
internal data class RelaysUiState(
    val lists: AccountRelayListsFfi?,
    val publication: RelayPublicationState = RelayPublicationState(),
    val busy: Boolean = false,
)

/** The user-facing name of a relay list kind the projection reports as missing. */
internal val MissingRelayListKindFfi.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MissingRelayListKindFfi.NIP65 -> R.string.nip_65
            MissingRelayListKindFfi.INBOX -> R.string.inbox
        }

/**
 * One account's operation gate, shared by every visit to Relays. Claims synchronously before launching so
 * publication, edits and refreshes cannot overlap; the mutation itself survives leaving the screen.
 * All callers run on the main thread, as does WhiteNoiseAppState.launchMutation.
 */
internal class RelayOperationState {
    var busy by mutableStateOf(false)
        private set

    /** Starts an operation only when idle, releasing the gate even when the operation or launcher fails. */
    fun launch(
        launcher: (suspend () -> Unit) -> Unit,
        onStarted: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        var launched = false
        try {
            onStarted()
            launcher {
                try {
                    block()
                } finally {
                    busy = false
                }
            }
            launched = true
        } finally {
            if (!launched) busy = false
        }
    }
}

// Values contain no AppState reference, allowing a disposed application state to leave the weak registry.
private val relayOperations = WeakHashMap<WhiteNoiseAppState, MutableMap<String?, RelayOperationState>>()

/** Keeps the same gate across navigation/recomposition while keeping different accounts independent. */
internal fun WhiteNoiseAppState.relayOperationState(account: String?): RelayOperationState =
    synchronized(relayOperations) {
        relayOperations.getOrPut(this) { mutableMapOf() }.getOrPut(account) { RelayOperationState() }
    }

/** A retry may add an existing URL to a missing selected role after a partial multi-list publication. */
internal fun missingRelayRoles(
    existing: List<AccountRelay>,
    url: String,
    selected: Set<AccountRelayRole>,
): Set<AccountRelayRole> {
    val normalized = normalizeRelayUrls(listOf(url)).singleOrNull() ?: return selected
    val present = existing.filter { normalized in normalizeRelayUrls(listOf(it.url)) }.flatMap { it.roles }.toSet()
    return selected - present
}
