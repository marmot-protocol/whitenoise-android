package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NostrEventCardResolverTest {
    @Test
    fun exactEventLookupSingleFlightsAndRejectsMismatches() =
        runTest {
            val requested = "a".repeat(64)
            var queryCount = 0
            var filter: JSONObject? = null
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, exactFilter ->
                        if (exactFilter.has("ids")) {
                            queryCount += 1
                            filter = exactFilter
                            listOf(event(id = "b".repeat(64)), event(id = requested, kind = 1, content = "Hello"))
                        } else {
                            emptyList()
                        }
                    },
                    verifyEvent = { true },
                )
            val reference = NostrEventReference.Event(requested)

            val first = resolver.state(reference)
            val second = resolver.state(reference)
            assertSame(first, second)
            runCurrent()

            assertEquals(1, queryCount)
            assertEquals(requested, filter?.getJSONArray("ids")?.getString(0))
            val loaded = first.value as NostrEventCardState.Loaded
            assertEquals(NostrEventCardKind.Note, loaded.card.kind)
            assertEquals("Hello", loaded.card.summary)
            resolver.close()
        }

    @Test
    fun validatedRelayHintsAreQueriedBeforeManagedRelaysWithinTheSharedCap() =
        runTest {
            val requested = "a".repeat(64)
            var queriedRelays = emptyList<String>()
            val hints =
                listOf(
                    "wss://hint-one.example",
                    "wss://hint-two.example",
                    "wss://ignored-after-validation.example",
                )
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = {
                        listOf(
                            "wss://managed-one.example",
                            "wss://managed-two.example",
                            "wss://managed-three.example",
                        )
                    },
                    relayHintProvider = { rawHints ->
                        assertEquals(hints, rawHints)
                        rawHints.take(2)
                    },
                    fetchEvents = { relays, _ ->
                        queriedRelays = relays
                        listOf(event(requested))
                    },
                    verifyEvent = { true },
                )

            val state =
                resolver.state(
                    NostrEventReference.Event(
                        eventIdHex = requested,
                        relayHints = hints,
                    ),
                )
            runCurrent()

            assertEquals(
                listOf(
                    "wss://hint-one.example",
                    "wss://hint-two.example",
                    "wss://managed-one.example",
                    "wss://managed-two.example",
                ),
                queriedRelays,
            )
            assertTrue(state.value is NostrEventCardState.Loaded)
            resolver.close()
        }

    @Test
    fun hintedRelayValidationRejectsUnsafeEndpointsBeforeDial() =
        runTest {
            val resolveChecked = mutableListOf<String>()

            val accepted =
                safePublicEventRelayHints(
                    hints =
                        listOf(
                            "ws://public.example",
                            "wss://127.0.0.1",
                            "wss://user:pass@public.example",
                            "wss://public.example:8443",
                            "wss://safe.example",
                            "wss://rebind.example",
                        ),
                    passesResolveTimeCheck = { url ->
                        resolveChecked += url
                        url != "wss://rebind.example"
                    },
                )

            assertEquals(listOf("wss://safe.example"), accepted)
            assertEquals(
                listOf("wss://safe.example", "wss://rebind.example"),
                resolveChecked,
            )
        }

    @Test
    fun relayHintResolutionConcurrencyIsBounded() =
        runTest {
            var activeResolutions = 0
            var peakResolutions = 0
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { emptyList() },
                    relayHintProvider = {
                        activeResolutions += 1
                        peakResolutions = maxOf(peakResolutions, activeResolutions)
                        try {
                            awaitCancellation()
                        } finally {
                            activeResolutions -= 1
                        }
                    },
                    fetchEvents = { _, _ -> error("must not query") },
                )

            repeat(4) { index ->
                resolver.state(
                    NostrEventReference.Event(
                        eventIdHex = index.toString().repeat(64),
                        relayHints = listOf("wss://hint-$index.example"),
                    ),
                )
            }
            runCurrent()

            assertEquals(3, peakResolutions)
            resolver.close()
            runCurrent()
            assertEquals(0, activeResolutions)
        }

    @Test
    fun optionalEventPointerMetadataDoesNotSplitOrRejectTheExactIdLookup() =
        runTest {
            val requested = "a".repeat(64)
            var queryCount = 0
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, filter ->
                        if (filter.has("ids")) {
                            queryCount += 1
                            listOf(event(requested, pubkey = "b".repeat(64), kind = 1))
                        } else {
                            emptyList()
                        }
                    },
                    verifyEvent = { true },
                )

            val unconstrained = resolver.state(NostrEventReference.Event(requested))
            val pointerWithStaleMetadata =
                resolver.state(
                    NostrEventReference.Event(
                        eventIdHex = requested,
                        authorPubkeyHex = "f".repeat(64),
                        kind = 30_023u,
                    ),
                )
            runCurrent()

            assertSame(unconstrained, pointerWithStaleMetadata)
            assertEquals(1, queryCount)
            assertTrue(unconstrained.value is NostrEventCardState.Loaded)
            resolver.close()
        }

    @Test
    fun addressLookupUsesExactCoordinateAndDeterministicNewestSelection() =
        runTest {
            val author = "c".repeat(64)
            var filter: JSONObject? = null
            val lowerId = "1".repeat(64)
            val higherId = "f".repeat(64)
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, exactFilter ->
                        if (exactFilter.has("#d")) {
                            filter = exactFilter
                            listOf(
                                event(higherId, author, 30_023, 9, listOf(listOf("d", "entry"))),
                                event(lowerId, author, 30_023, 9, listOf(listOf("d", "entry"))),
                                event("0".repeat(64), author, 30_023, 10, listOf(listOf("d", "other"))),
                            )
                        } else {
                            emptyList()
                        }
                    },
                    verifyEvent = { true },
                )
            val state = resolver.state(NostrEventReference.Address(30_023u, author, "entry"))
            runCurrent()

            assertEquals(author, filter?.getJSONArray("authors")?.getString(0))
            assertEquals(30_023L, filter?.getJSONArray("kinds")?.getLong(0))
            assertEquals("entry", filter?.getJSONArray("#d")?.getString(0))
            assertEquals(lowerId, (state.value as NostrEventCardState.Loaded).card.eventIdHex)
            resolver.close()
        }

    @Test
    fun addressLookupRejectsAValidEventFromTheWrongCoordinate() =
        runTest {
            val author = "c".repeat(64)
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, _ ->
                        listOf(
                            event(
                                id = "a".repeat(64),
                                pubkey = author,
                                kind = 30_023,
                                tags = listOf(listOf("d", "another-entry")),
                            ),
                        )
                    },
                    verifyEvent = { true },
                )

            val state = resolver.state(NostrEventReference.Address(30_023u, author, "entry"))
            runCurrent()

            assertEquals(NostrEventCardState.Invalid, state.value)
            resolver.close()
        }

    @Test
    fun verifiedKindZeroMetadataEnrichesTheCardWithoutBlockingTheEvent() =
        runTest {
            val requested = "a".repeat(64)
            val author = "c".repeat(64)
            val metadataStarted = CompletableDeferred<Unit>()
            val releaseMetadata = CompletableDeferred<Unit>()
            var metadataFilter: JSONObject? = null
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, filter ->
                        if (filter.has("ids")) {
                            listOf(event(requested, pubkey = author, content = "Hello"))
                        } else {
                            metadataFilter = filter
                            metadataStarted.complete(Unit)
                            releaseMetadata.await()
                            listOf(
                                event(
                                    id = "d".repeat(64),
                                    pubkey = author,
                                    kind = 0,
                                    createdAt = 2,
                                    content =
                                        "{\"display_name\":\"  Alice   Rivers  \"," +
                                            "\"picture\":\" https://cdn.example/alice.jpg \"}",
                                ),
                            )
                        }
                    },
                    verifyEvent = { true },
                )

            val state = resolver.state(NostrEventReference.Event(requested))
            metadataStarted.await()

            assertEquals(null, (state.value as NostrEventCardState.Loaded).card.authorMetadata)
            releaseMetadata.complete(Unit)
            runCurrent()

            val metadata = (state.value as NostrEventCardState.Loaded).card.authorMetadata
            assertEquals("Alice Rivers", metadata?.displayName)
            assertEquals("https://cdn.example/alice.jpg", metadata?.pictureUrl)
            assertEquals(author, metadataFilter?.getJSONArray("authors")?.getString(0))
            assertEquals(0L, metadataFilter?.getJSONArray("kinds")?.getLong(0))
            assertEquals(1, metadataFilter?.getInt("limit"))
            resolver.close()
        }

    @Test
    fun emptyInvalidAndFailedQueriesRemainRetryable() =
        runTest {
            val reference = NostrEventReference.Event("a".repeat(64))
            var calls = 0
            val outcomes =
                ArrayDeque<List<NostrEvent>>().apply {
                    add(emptyList())
                    add(listOf(event("a".repeat(64))))
                }
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, _ ->
                        calls += 1
                        outcomes.removeFirst()
                    },
                    verifyEvent = { calls > 2 },
                )
            val state = resolver.state(reference)
            runCurrent()
            assertEquals(NostrEventCardState.NotFound, state.value)

            resolver.retry(reference)
            runCurrent()
            assertEquals(NostrEventCardState.Invalid, state.value)
            assertEquals(2, calls)
            resolver.close()

            val failed =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { emptyList() },
                    fetchEvents = { _, _ -> error("must not query") },
                )
            val failedState = failed.state(reference)
            runCurrent()
            assertEquals(NostrEventCardState.Failed, failedState.value)
            failed.close()
        }

    @Test
    fun closingConversationCancelsInFlightLookup() =
        runTest {
            var cancelled = false
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, _ ->
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

    @Test
    fun rendererRegistryBoundsHostileMetadataAndUsesTypedFallbacks() {
        val article =
            event(
                id = "a".repeat(64),
                kind = 30_023,
                tags = listOf(listOf("title", "T".repeat(400)), listOf("summary", "S".repeat(800))),
                content = "# Full article\n\nReadable body",
            ).toCardModel()
        val video =
            event(
                "b".repeat(64),
                kind = 34_235,
                tags =
                    listOf(
                        listOf(
                            "imeta",
                            "url https://cdn.example/video.mp4",
                            "m video/mp4",
                            "duration 125",
                            "dim 1920x1080",
                        ),
                    ),
            ).toCardModel()
        val release = event("c".repeat(64), kind = 30_063, tags = listOf(listOf("version", "2026.8.1"))).toCardModel()
        val file =
            event(
                "d".repeat(64),
                kind = 1_063,
                tags = listOf(listOf("m", "audio/ogg"), listOf("size", "2048")),
            ).toCardModel()
        val generic = event("e".repeat(64), kind = 7_777, content = "body").toCardModel()

        assertEquals(NostrEventCardKind.Article, article.kind)
        assertEquals(160, article.title?.length)
        assertEquals(420, article.summary?.length)
        assertEquals("# Full article\n\nReadable body", article.readerBody)
        assertEquals(listOf("2:05", "1920x1080"), video.metadata)
        assertEquals("https://cdn.example/video.mp4", video.mediaUrl)
        assertEquals("video/mp4", video.mediaMimeType)
        assertEquals(listOf("2026.8.1"), release.metadata)
        assertEquals(listOf("audio/ogg", "2.0 KB"), file.metadata)
        assertEquals(NostrEventCardKind.Generic, generic.kind)
        assertEquals("body", generic.summary)
    }

    /** Verifies only readable note and article kinds retain a bounded full-content body. */
    @Test
    fun noteRetainsFullReaderBodyWhileOtherTypedKindsRemainUnchanged() {
        val fullNote = "n".repeat(70_000)
        val note = event("a".repeat(64), kind = 1, content = fullNote).toCardModel()
        val article = event("b".repeat(64), kind = 30_023, content = "Full article").toCardModel()
        val nonReaders =
            listOf(
                event("c".repeat(64), kind = 34_235, content = "Video").toCardModel(),
                event("d".repeat(64), kind = 30_063, content = "Release").toCardModel(),
                event("e".repeat(64), kind = 1_063, content = "File").toCardModel(),
                event("f".repeat(64), kind = 7_777, content = "Generic").toCardModel(),
            )

        assertEquals("n".repeat(420), note.summary)
        assertEquals("n".repeat(64 * 1_024), note.readerBody)
        assertEquals("Full article", article.readerBody)
        assertTrue(nonReaders.all { it.readerBody == null })
    }

    @Test
    fun textBoundsPreserveCompleteUnicodeCodePoints() {
        val emoji = "\uD83D\uDE00"
        val boundedField = ("a".repeat(159) + emoji + "tail").safeField()
        val boundedExcerpt =
            event(
                id = "a".repeat(64),
                content = "b".repeat(419) + emoji + "tail",
            ).toCardModel().summary

        assertEquals("a".repeat(159) + emoji, boundedField)
        assertEquals("b".repeat(419) + emoji, boundedExcerpt)
    }

    @Test
    fun videoPlaybackUrlAcceptsOnlyPublicDefaultPortHttps() {
        assertEquals("https://cdn.example/video.mp4", safeNostrMediaUrl(" https://cdn.example/video.mp4 "))
        assertEquals(null, safeNostrMediaUrl("http://cdn.example/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://user:pass@cdn.example/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://cdn.example:8443/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://127.0.0.1/video.mp4"))
        assertEquals(null, safeNostrMediaUrl("https://[::1]/video.mp4"))
    }

    @Test
    fun hlsVideoMetadataRemainsPlayableWithoutAFileExtension() {
        val video =
            event(
                id = "a".repeat(64),
                kind = 34_236,
                tags =
                    listOf(
                        listOf(
                            "imeta",
                            "url https://media.example/watch/stream",
                            "m application/vnd.apple.mpegurl",
                        ),
                    ),
            ).toCardModel()

        assertEquals("https://media.example/watch/stream", video.mediaUrl)
        assertEquals("application/vnd.apple.mpegurl", video.mediaMimeType)
    }

    @Test
    fun relayDiscoveryAndResolvedStateStayBoundedAcrossRows() =
        runTest {
            var relayLoads = 0
            var queries = 0
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = {
                        relayLoads += 1
                        listOf("wss://relay.example")
                    },
                    fetchEvents = { _, filter ->
                        if (filter.has("ids")) {
                            queries += 1
                            listOf(event(filter.getJSONArray("ids").getString(0), content = "resolved"))
                        } else {
                            emptyList()
                        }
                    },
                    verifyEvent = { true },
                    maxEntries = 2,
                )
            val first = NostrEventReference.Event("a".repeat(64))
            val second = NostrEventReference.Event("b".repeat(64))
            val third = NostrEventReference.Event("c".repeat(64))

            val firstState = resolver.state(first)
            runCurrent()
            resolver.state(second)
            runCurrent()
            resolver.state(third)
            runCurrent()
            resolver.retry(first, firstState)
            runCurrent()

            assertEquals(2, relayLoads)
            assertEquals(4, queries)
            assertTrue(firstState.value is NostrEventCardState.Loaded)
            resolver.close()
        }

    @Test
    fun nestedReferenceContentDoesNotStartAnotherLookup() =
        runTest {
            var queries = 0
            val requested = "a".repeat(64)
            val nested = "note1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsn9e8p"
            val resolver =
                NostrEventCardResolver(
                    parentScope = this,
                    verificationDispatcher = UnconfinedTestDispatcher(testScheduler),
                    relayProvider = { listOf("wss://relay.example") },
                    fetchEvents = { _, filter ->
                        if (filter.has("ids")) {
                            queries += 1
                            listOf(event(requested, content = "See $nested"))
                        } else {
                            emptyList()
                        }
                    },
                    verifyEvent = { true },
                )

            val state = resolver.state(NostrEventReference.Event(requested))
            runCurrent()

            assertEquals(1, queries)
            assertEquals("See $nested", (state.value as NostrEventCardState.Loaded).card.summary)
            resolver.close()
        }

    @Test
    fun everyInitialMediaKindUsesTheTypedRenderer() {
        listOf(21, 22, 34_235, 34_236).forEach { kind ->
            assertEquals(NostrEventCardKind.Video, event("a".repeat(64), kind = kind).toCardModel().kind)
        }
        assertEquals(NostrEventCardKind.Note, event("b".repeat(64), kind = 1).toCardModel().kind)
        assertEquals(NostrEventCardKind.Article, event("c".repeat(64), kind = 30_023).toCardModel().kind)
        assertEquals(NostrEventCardKind.Release, event("d".repeat(64), kind = 30_063).toCardModel().kind)
        assertEquals(NostrEventCardKind.File, event("e".repeat(64), kind = 1_063).toCardModel().kind)
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
