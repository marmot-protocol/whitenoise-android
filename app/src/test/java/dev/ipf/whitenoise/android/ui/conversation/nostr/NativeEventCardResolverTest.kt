package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Covers the MDK cached-read and refresh lifecycle behind every public event card (#2238). */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeEventCardResolverTest {
    /** A failed projection read is not a miss of record: the card stays Loading and MDK's refresh still settles it. */
    @Test
    fun nativeCacheReadFailureFallsBackToNativeResolve() =
        runTest {
            val loaded = NostrEventCardState.Loaded(event(id = ID).toCardModel())
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { error("cache unavailable") },
                    refresh = { loaded },
                )
            val state = resolver.state(NostrEventReference.Event(ID))
            assertEquals(NostrEventCardState.Loading, state.value)
            runCurrent()
            assertEquals(loaded, state.value)
            resolver.close()
        }

    /** A late author result after close never reaches the retired card. */
    @Test
    fun closedNativeResolverRejectsLateAuthorEnrichment() =
        runTest {
            val loaded = NostrEventCardState.Loaded(event(id = ID).toCardModel())
            val author = CompletableDeferred<NostrEventCardState.Loaded>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { loaded } },
                    refresh = { loaded },
                    refreshAuthor = { withContext(NonCancellable) { author.await() } },
                )
            val state = resolver.state(NostrEventReference.Event(ID))
            assertEquals(NostrEventCardState.Loading, state.value)
            runCurrent()
            assertEquals(loaded, state.value)
            resolver.close()
            author.complete(
                loaded.copy(card = loaded.card.copy(authorMetadata = NostrEventAuthorMetadata("Late", null))),
            )
            runCurrent()
            assertEquals(loaded, state.value)
        }

    /** Retrying a loaded card keeps it on screen, and only the newest refresh may settle it. */
    @Test
    fun nativeRetryRejectsSupersededRefreshAndDoesNotReplaceCachedCardWithLoading() =
        runTest {
            val cached = NostrEventCardState.Loaded(event(id = ID, content = "Retained").toCardModel())
            val refreshes = mutableListOf<CompletableDeferred<NostrEventCardState?>>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { cached } },
                    refresh = { CompletableDeferred<NostrEventCardState?>().also { refreshes += it }.await() },
                )
            val reference = NostrEventReference.Event(ID)
            val state = resolver.state(reference)
            assertEquals(NostrEventCardState.Loading, state.value)
            runCurrent()
            resolver.retry(reference, state)
            assertEquals(cached, state.value)
            runCurrent()
            refreshes.first().complete(NostrEventCardState.Invalid)
            refreshes.last().complete(cached)
            runCurrent()
            assertEquals(cached, state.value)
            resolver.close()
        }

    /** A reopened conversation starts from its snapshot: the first value is the cached card, and offline keeps it. */
    @Test
    fun nativeCachePublishesBeforeRefreshAndRetainsCardOnOfflineReopen() =
        runTest {
            val cached = NostrEventCardState.Loaded(event(id = ID, content = "Retained").toCardModel())
            val snapshots = NostrEventCardSnapshots()
            repeat(2) { opening ->
                val refresh = CompletableDeferred<Unit>()
                val cacheReads = mutableListOf<List<String>>()
                val resolver =
                    NostrEventCardResolver(
                        parentScope = this,
                        readCached = { references ->
                            cacheReads += references
                            references.map { cached }
                        },
                        refresh = { raw ->
                            assertEquals("nostr:retained", raw)
                            refresh.await()
                            error("offline")
                        },
                        snapshots = snapshots,
                    )
                val state = resolver.state(NostrEventReference.Event(ID), "nostr:retained")
                val expectedInitial = if (opening == 0) NostrEventCardState.Loading else cached
                assertEquals(expectedInitial, state.value)
                runCurrent()
                assertEquals(cached, state.value)
                // The second opening is seeded, so it needs no cache read at all.
                assertEquals(if (opening == 0) listOf(listOf("nostr:retained")) else emptyList(), cacheReads)
                refresh.complete(Unit)
                runCurrent()
                assertEquals(cached, state.value)
                resolver.close()
            }
        }

    /** A late refresh after close is rejected, and a card with nothing cached stays Loading. */
    @Test
    fun closedNativeResolverRejectsLateRefresh() =
        runTest {
            val refresh = CompletableDeferred<NostrEventCardState?>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { null } },
                    refresh = { refresh.await() },
                )
            val state = resolver.state(NostrEventReference.Event(ID))
            assertEquals(NostrEventCardState.Loading, state.value)
            runCurrent()
            resolver.close()
            refresh.complete(NostrEventCardState.Loaded(event(id = ID, content = "Late").toCardModel()))
            runCurrent()
            assertEquals(NostrEventCardState.Loading, state.value)
        }

    /** A recreated resolver over the same snapshots starts loaded, never flashing the card back to Loading. */
    @Test
    fun recreatedResolverSeedsFromSnapshotsWithoutALoadingFrame() =
        runTest {
            val cached = NostrEventCardState.Loaded(event(id = ID, content = "Seen").toCardModel())
            val snapshots = NostrEventCardSnapshots()
            snapshots.put(NostrEventReference.Event(ID).stableId, cached)
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { error("a seeded card needs no cache read") },
                    refresh = { null },
                    snapshots = snapshots,
                )
            val state = resolver.state(NostrEventReference.Event(ID))
            assertSame(cached, state.value)
            runCurrent()
            // MDK still has nothing newer: the known-good card stays, as the same instance.
            assertSame(cached, state.value)
            resolver.close()
        }

    /** A refresh carries cached author metadata forward when MDK's result for the same event lacks it. */
    @Test
    fun refreshKeepsKnownAuthorMetadataForTheSameEvent() =
        runTest {
            val author = NostrEventAuthorMetadata("Alice", "https://cdn.example/alice.jpg")
            val base = event(id = ID, content = "Body").toCardModel()
            val cached = NostrEventCardState.Loaded(base.copy(authorMetadata = author))
            val snapshots = NostrEventCardSnapshots().apply { put(NostrEventReference.Event(ID).stableId, cached) }
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { error("unused") },
                    refresh = { NostrEventCardState.Loaded(base) },
                    snapshots = snapshots,
                )
            val state = resolver.state(NostrEventReference.Event(ID))
            assertSame(cached, state.value)
            runCurrent()
            assertSame(cached, state.value)
            resolver.close()
        }

    /** MDK's authorized deletion replaces a cached card, while a transient miss or failure never does. */
    @Test
    fun deletionSettlesTheCardButMissesAndFailuresKeepIt() =
        runTest {
            val cached = NostrEventCardState.Loaded(event(id = ID, content = "Body").toCardModel())
            val outcomes = ArrayDeque<() -> NostrEventCardState?>()
            outcomes += { null }
            outcomes += { error("relay outage") }
            outcomes += { NostrEventCardState.NotFound }
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references -> references.map { cached } },
                    refresh = { outcomes.removeFirst()() },
                )
            val reference = NostrEventReference.Event(ID)
            val state = resolver.state(reference)
            runCurrent()
            assertEquals(cached, state.value)
            resolver.retry(reference, state)
            runCurrent()
            assertEquals(cached, state.value)
            resolver.retry(reference, state)
            runCurrent()
            assertEquals(NostrEventCardState.NotFound, state.value)
            resolver.close()
        }

    /** Cards composed together share batched cache reads of at most sixteen references each. */
    @Test
    fun cacheReadsAreBatchedAcrossCards() =
        runTest {
            val batches = mutableListOf<Int>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    readCached = { references ->
                        batches += references.size
                        references.map { null }
                    },
                    refresh = { null },
                )
            repeat(20) { index -> resolver.state(NostrEventReference.Event(index.toString(16).padStart(64, '0'))) }
            runCurrent()
            assertEquals(listOf(16, 4), batches)
            resolver.close()
        }

    /** A signed-looking event fixture; signatures are MDK's concern and never checked here. */
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

    private companion object {
        val ID = "a".repeat(64)
    }
}
