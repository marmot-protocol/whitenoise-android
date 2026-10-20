package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Public event card state stays single-flight, bounded, retryable and conversation-scoped over MDK's contract. */
@OptIn(ExperimentalCoroutinesApi::class)
class NostrEventCardResolverTest {
    /** Two cards for the same event share one state and one refresh, keyed by the reference's stable id. */
    @Test
    fun sameEventSingleFlightsAcrossOccurrences() =
        runTest {
            val requested = "a".repeat(64)
            val refreshed = mutableListOf<String>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = { raw ->
                        refreshed += raw
                        NostrEventCardState.Loaded(event(requested, content = "Hello").toCardModel())
                    },
                )
            val first = resolver.state(NostrEventReference.Event(requested))
            val withStaleMetadata =
                resolver.state(
                    NostrEventReference.Event(eventIdHex = requested, authorPubkeyHex = "f".repeat(64), kind = 30_023u),
                )
            assertSame(first, withStaleMetadata)
            runCurrent()

            assertEquals(1, refreshed.size)
            assertEquals("Hello", (first.value as NostrEventCardState.Loaded).card.summary)
            resolver.close()
        }

    /** The authored reference, relay hints included, is what MDK is asked to read and refresh. */
    @Test
    fun authoredReferenceIsPassedToMdkUnchanged() =
        runTest {
            val authored = "nevent1qqs8f4exampleauthoredreferencewithrelayhints"
            val cached = mutableListOf<String>()
            val refreshed = mutableListOf<String>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references ->
                        cached += references
                        references.map { null }
                    },
                    refresh = { raw ->
                        refreshed += raw
                        null
                    },
                )
            resolver.state(NostrEventReference.Event("a".repeat(64), relayHints = listOf("wss://hint")), authored)
            runCurrent()

            assertEquals(listOf(authored), cached)
            assertEquals(listOf(authored), refreshed)
            resolver.close()
        }

    /** Missing, invalid and failed outcomes each settle a never-seen card and remain retryable. */
    @Test
    fun missingInvalidAndFailedOutcomesRemainRetryable() =
        runTest {
            val reference = NostrEventReference.Event("a".repeat(64))
            val outcomes = ArrayDeque<() -> NostrEventCardState?>()
            outcomes += { null }
            outcomes += { NostrEventCardState.Invalid }
            outcomes += { error("relay outage") }
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = { outcomes.removeFirst()() },
                )
            val state = resolver.state(reference)
            runCurrent()
            assertEquals(NostrEventCardState.NotFound, state.value)

            resolver.retry(reference, state)
            assertEquals(NostrEventCardState.Loading, state.value)
            runCurrent()
            assertEquals(NostrEventCardState.Invalid, state.value)

            resolver.retry(reference, state)
            runCurrent()
            assertEquals(NostrEventCardState.Failed, state.value)
            resolver.close()
        }

    /** Leaving the conversation cancels the in-flight refresh. */
    @Test
    fun closingConversationCancelsInFlightLookup() =
        runTest {
            var cancelled = false
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                )
            resolver.state(NostrEventReference.Event("a".repeat(64)))
            runCurrent()
            resolver.close()
            runCurrent()
            assertTrue(cancelled)
        }

    /** Resolved state stays bounded across rows, and an evicted card resolves again on retry. */
    @Test
    fun resolvedStateStaysBoundedAcrossRows() =
        runTest {
            var refreshes = 0
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = { raw ->
                        refreshes += 1
                        NostrEventCardState.Loaded(event(raw, content = "resolved").toCardModel())
                    },
                    maxEntries = 2,
                )
            val first = NostrEventReference.Event("a".repeat(64))
            val firstState = resolver.state(first)
            runCurrent()
            resolver.state(NostrEventReference.Event("b".repeat(64)))
            runCurrent()
            resolver.state(NostrEventReference.Event("c".repeat(64)))
            runCurrent()
            resolver.retry(first, firstState)
            runCurrent()

            assertEquals(4, refreshes)
            assertTrue(firstState.value is NostrEventCardState.Loaded)
            resolver.close()
        }

    /** References inside a resolved event's content are shown as text and never start another lookup. */
    @Test
    fun nestedReferenceContentDoesNotStartAnotherLookup() =
        runTest {
            var refreshes = 0
            val requested = "a".repeat(64)
            val nested = "note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsn9e8p"
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = {
                        refreshes += 1
                        NostrEventCardState.Loaded(event(requested, content = "See $nested").toCardModel())
                    },
                )
            val state = resolver.state(NostrEventReference.Event(requested))
            runCurrent()

            assertEquals(1, refreshes)
            assertEquals("See $nested", (state.value as NostrEventCardState.Loaded).card.summary)
            resolver.close()
        }

    /** A signed-looking event fixture; MDK verifies signatures before an event reaches a card. */
    private fun event(
        id: String,
        pubkey: String = "c".repeat(64),
        kind: Int = 1,
        createdAt: Long = 1,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )
}
