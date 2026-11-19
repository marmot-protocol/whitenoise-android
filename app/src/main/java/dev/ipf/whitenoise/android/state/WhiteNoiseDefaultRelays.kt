package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.core.MarmotClient

/** Publishes one relay list through a validated setter, returning the updated projection, or null on failure. */
internal typealias RelayListPublisher = suspend (RelayListKind, RelayListEditPlan) -> AccountRelayListsFfi?

/**
 * White Noise's relays for one list, the split a new account publishes: NIP-65 gets [MarmotClient.accountRelays],
 * the White Noise relays plus general-purpose ones other clients can use, and Inbox gets [MarmotClient.bootstrapRelays].
 */
internal fun whiteNoiseDefaultRelays(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> MarmotClient.accountRelays
        RelayListKind.Inbox -> MarmotClient.bootstrapRelays
    }

/**
 * Publishes White Noise's NIP-65 defaults, then its Inbox defaults, through the caller's validated setter. A NIP-65
 * failure stops before Inbox, so a failed Restore never leaves Inbox restored on its own.
 */
internal suspend fun restoreDefaultRelayKinds(publish: RelayListPublisher): AccountRelayListsFfi? =
    publish(RelayListKind.Nip65, RelayListEditPlan(whiteNoiseDefaultRelays(RelayListKind.Nip65)))
        ?.let { publish(RelayListKind.Inbox, RelayListEditPlan(whiteNoiseDefaultRelays(RelayListKind.Inbox))) }
