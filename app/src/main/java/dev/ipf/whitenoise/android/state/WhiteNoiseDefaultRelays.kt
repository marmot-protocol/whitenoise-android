package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.MarmotClient

/**
 * White Noise's relays for one list, the split a new account publishes: NIP-65 gets [MarmotClient.accountRelays],
 * the White Noise relays plus general-purpose ones other clients can use, and Inbox gets [MarmotClient.bootstrapRelays].
 */
internal fun whiteNoiseDefaultRelays(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> MarmotClient.accountRelays
        RelayListKind.Inbox -> MarmotClient.bootstrapRelays
    }
