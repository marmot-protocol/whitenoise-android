package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.marmotkit.RelayListFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Missing-list recovery must never reset an already published relay kind to the default addresses. */
class AccountRelayPublicationTest {
    /** A custom posting list survives recovery of the missing receiving list. */
    @Test
    fun missingInboxPreservesCustomPostingList() =
        runTest {
            val current = lists(listOf(CUSTOM), emptyList(), listOf(MissingRelayListKindFfi.INBOX))
            val recovered = lists(listOf(CUSTOM), listOf(DEFAULT), emptyList())
            val calls = mutableListOf<RelayListKind>()
            val result =
                publishMissingAccountRelayKinds(current) { kind, plan ->
                    calls += kind
                    assertEquals(listOf(DEFAULT), plan.relays)
                    recovered
                }
            assertEquals(listOf(RelayListKind.Inbox), calls)
            assertSame(recovered, result)
            assertEquals(listOf(CUSTOM), result?.nip65?.relays)
        }

    /** A custom receiving list survives recovery of the missing posting list. */
    @Test
    fun missingPostingListPreservesCustomInbox() =
        runTest {
            val current = lists(emptyList(), listOf(CUSTOM), listOf(MissingRelayListKindFfi.NIP65))
            val recovered = lists(listOf(DEFAULT), listOf(CUSTOM), emptyList())
            val calls = mutableListOf<RelayListKind>()
            val result =
                publishMissingAccountRelayKinds(current) { kind, _ ->
                    calls += kind
                    recovered
                }
            assertEquals(listOf(RelayListKind.Nip65), calls)
            assertEquals(listOf(CUSTOM), result?.inbox?.relays)
        }

    /** Recovery publishes both missing kinds individually, allowing the validated per-kind setter to reject either. */
    @Test
    fun bothMissingKindsArePublishedOnceEach() =
        runTest {
            val current = lists(emptyList(), emptyList(), MissingRelayListKindFfi.entries)
            val calls = mutableListOf<RelayListKind>()
            val result =
                publishMissingAccountRelayKinds(current) { kind, _ ->
                    calls += kind
                    if (calls.size == 1) {
                        lists(listOf(DEFAULT), emptyList(), listOf(MissingRelayListKindFfi.INBOX))
                    } else {
                        lists(listOf(DEFAULT), listOf(DEFAULT), emptyList())
                    }
                }
            assertEquals(listOf(RelayListKind.Nip65, RelayListKind.Inbox), calls)
            assertEquals(true, result?.complete)
        }

    /** A failed second setter leaves recovery failed; retry uses the refreshed projection and skips the first kind. */
    @Test
    fun partialFailureRetryDoesNotRepublishTheSuccessfulKind() =
        runTest {
            val partial = lists(listOf(DEFAULT), emptyList(), listOf(MissingRelayListKindFfi.INBOX))
            val calls = mutableListOf<RelayListKind>()
            val missing = lists(emptyList(), emptyList(), MissingRelayListKindFfi.entries)
            val failed =
                publishMissingAccountRelayKinds(missing) { kind, _ ->
                    calls += kind
                    if (kind == RelayListKind.Nip65) partial else null
                }
            assertNull(failed)
            assertEquals(listOf(RelayListKind.Nip65, RelayListKind.Inbox), calls)
            calls.clear()
            publishMissingAccountRelayKinds(partial) { kind, _ ->
                calls += kind
                lists(listOf(DEFAULT), listOf(DEFAULT), emptyList())
            }
            assertEquals(listOf(RelayListKind.Inbox), calls)
        }

    /** Fully published custom lists cause no native write. */
    @Test
    fun completeListsDoNotPublish() =
        runTest {
            val current = lists(listOf(CUSTOM), listOf(CUSTOM), emptyList())
            val result = publishMissingAccountRelayKinds(current) { _, _ -> error("Unexpected relay publication") }
            assertSame(current, result)
        }

    /** A minimal native projection carrying custom/default relay addresses and publication readiness. */
    private fun lists(
        posting: List<String>,
        receiving: List<String>,
        missing: List<MissingRelayListKindFfi>,
    ) = AccountRelayListsFfi(
        complete = missing.isEmpty(),
        missing = missing,
        defaultRelays = listOf(DEFAULT),
        bootstrapRelays = emptyList(),
        nip65 = RelayListFfi(kind = 10_002uL, relays = posting),
        inbox = RelayListFfi(kind = 10_050uL, relays = receiving),
    )

    private companion object {
        const val CUSTOM = "wss://custom.example.com"
        const val DEFAULT = "wss://default.example.com"
    }
}
