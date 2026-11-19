package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.MarmotClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** Restore default relays publishes White Noise's own relays, never the account's projected `defaultRelays`. */
class RestoreDefaultRelaysTest {
    /** NIP-65 gets the White Noise and general-purpose relays, and Inbox gets only the White Noise relays. */
    @Test
    fun eachListGetsTheSplitANewAccountPublishes() {
        assertEquals(MarmotClient.accountRelays, whiteNoiseDefaultRelays(RelayListKind.Nip65))
        assertEquals(MarmotClient.bootstrapRelays, whiteNoiseDefaultRelays(RelayListKind.Inbox))
    }
}
