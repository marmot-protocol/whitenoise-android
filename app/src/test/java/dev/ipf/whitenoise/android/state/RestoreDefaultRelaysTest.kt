package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.whitenoise.android.core.MarmotClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Restore default relays publishes White Noise's own relays, never the account's projected `defaultRelays`. */
class RestoreDefaultRelaysTest {
    /** NIP-65 gets the White Noise and general-purpose relays, then Inbox gets only the White Noise relays. */
    @Test
    fun restorePublishesWhiteNoiseRelaysToEachList() =
        runTest {
            val published = mutableListOf<Pair<RelayListKind, RelayListEditPlan>>()
            val restored = lists()

            val result =
                restoreDefaultRelayKinds { kind, plan ->
                    published += kind to plan
                    restored
                }

            assertSame(restored, result)
            assertEquals(
                listOf(
                    RelayListKind.Nip65 to RelayListEditPlan(MarmotClient.accountRelays),
                    RelayListKind.Inbox to RelayListEditPlan(MarmotClient.bootstrapRelays),
                ),
                published,
            )
        }

    /** A rejected NIP-65 publish stops the restore, so Inbox is never replaced on its own. */
    @Test
    fun failedPostingListStopsBeforeInbox() =
        runTest {
            val published = mutableListOf<RelayListKind>()

            val result =
                restoreDefaultRelayKinds { kind, _ ->
                    published += kind
                    null
                }

            assertNull(result)
            assertEquals(listOf(RelayListKind.Nip65), published)
        }

    /** A projection standing in for the lists MarmotKit returns after a publish. */
    private fun lists() =
        AccountRelayListsFfi(
            complete = true,
            missing = emptyList(),
            defaultRelays = listOf("wss://own.example.com"),
            bootstrapRelays = emptyList(),
            nip65 = RelayListFfi(kind = 10_002uL, relays = MarmotClient.accountRelays),
            inbox = RelayListFfi(kind = 10_050uL, relays = MarmotClient.bootstrapRelays),
        )
}
