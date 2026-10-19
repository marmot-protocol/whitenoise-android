package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEventCardResolverTest {
    @Test
    fun nativeCacheReadFailureFallsBackToNativeResolve() =
        runTest {
            val loaded = NostrEventCardState.Loaded(event(id = "a".repeat(64)).toCardModel())
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    relayProvider = { error("unused") },
                    nativePreview = { _, force ->
                        if (!force) error("cache unavailable")
                        loaded
                    },
                )
            val state = resolver.state(NostrEventReference.Event("a".repeat(64)))
            runCurrent()
            assertEquals(loaded, state.value)
            resolver.close()
        }

    @Test
    fun closedNativeResolverRejectsLateAuthorEnrichment() =
        runTest {
            val loaded = NostrEventCardState.Loaded(event(id = "a".repeat(64)).toCardModel())
            val author = CompletableDeferred<NostrEventCardState.Loaded>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    relayProvider = { error("unused") },
                    nativePreview = { _, _ -> loaded },
                    nativeAuthor = {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { author.await() }
                    },
                )
            val state = resolver.state(NostrEventReference.Event("a".repeat(64)))
            runCurrent()
            assertEquals(loaded, state.value)
            resolver.close()
            author.complete(
                loaded.copy(card = loaded.card.copy(authorMetadata = NostrEventAuthorMetadata("Late", null))),
            )
            runCurrent()
            assertEquals(loaded, state.value)
        }

    @Test
    fun nativeRetryRejectsSupersededRefreshAndDoesNotReplaceCachedCardWithLoading() =
        runTest {
            val cached = NostrEventCardState.Loaded(event(id = "a".repeat(64), content = "Retained").toCardModel())
            val refreshes = mutableListOf<CompletableDeferred<NostrEventCardState>>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    relayProvider = { error("unused") },
                    nativePreview = { _, force ->
                        if (force) {
                            CompletableDeferred<NostrEventCardState>().also { refreshes += it }.await()
                        } else {
                            cached
                        }
                    },
                )
            val reference = NostrEventReference.Event("a".repeat(64))
            val state = resolver.state(reference)
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

    @Test
    fun nativeCachePublishesBeforeRefreshAndRetainsCardOnOfflineReopen() =
        runTest {
            val cached = NostrEventCardState.Loaded(event(id = "a".repeat(64), content = "Retained").toCardModel())
            repeat(2) {
                val refresh = CompletableDeferred<Unit>()
                val calls = mutableListOf<Boolean>()
                val resolver =
                    NostrEventCardResolver(
                        parentScope = this,
                        relayProvider = { error("Native preview must own relay resolution") },
                        nativePreview = { raw, force ->
                            assertEquals("nostr:retained", raw)
                            calls += force
                            if (force) {
                                refresh.await()
                                error("offline")
                            }
                            cached
                        },
                    )
                val state = resolver.state(NostrEventReference.Event("a".repeat(64)), "nostr:retained")
                runCurrent()
                assertEquals(cached, state.value)
                assertEquals(listOf(false, true), calls)
                refresh.complete(Unit)
                runCurrent()
                assertEquals(cached, state.value)
                resolver.close()
            }
        }

    @Test
    fun closedNativeResolverRejectsLateRefresh() =
        runTest {
            val refresh = CompletableDeferred<NostrEventCardState>()
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    relayProvider = { error("unused") },
                    nativePreview = { _, force ->
                        if (force) refresh.await() else NostrEventCardState.NotFound
                    },
                )
            val state = resolver.state(NostrEventReference.Event("a".repeat(64)))
            runCurrent()
            resolver.close()
            refresh.complete(NostrEventCardState.Loaded(event(id = "a".repeat(64), content = "Late").toCardModel()))
            runCurrent()
            assertEquals(NostrEventCardState.Loading, state.value)
        }

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
