package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.NostrEventReference
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardResolver
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardSnapshots
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.conversation.nostr.eventCardDocument
import dev.ipf.whitenoise.android.ui.conversation.nostr.eventCardTimelineRecord
import dev.ipf.whitenoise.android.ui.conversation.nostr.noteBech32
import dev.ipf.whitenoise.android.ui.conversation.nostr.toCardModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first page's event-card warm through the production coordinator, snapshot store and resolver (#2238): what the
 * route shows at the moment it becomes ready is what a reader sees on the first committed frame.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InitialEventCardFirstFrameTest {
    /** A cold cache read slower than the profile budget, within the card budget, still yields a Loaded first value. */
    @Test
    fun slowColdCacheReadStillOpensWithTheCardLoaded() =
        runTest {
            val opened = openFirstPage(cacheReadMillis = COLD_CACHE_READ_MILLIS)

            advanceTimeBy(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS + 1L)
            runCurrent()

            assertEquals(HYDRATION_MILLIS + COLD_CACHE_READ_MILLIS, opened.readyAt)
            assertEquals(CACHED, opened.firstValue)
        }

    /** A cache read beyond the card budget cannot hold the route, which opens at the budget while the card resolves. */
    @Test
    fun cacheReadBeyondTheBudgetStillOpensTheRoute() =
        runTest {
            val opened = openFirstPage(cacheReadMillis = INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS * 5)

            advanceTimeBy(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS + 1L)
            runCurrent()

            assertEquals(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS, opened.readyAt)
            assertEquals(NostrEventCardState.Loading, opened.firstValue)
        }

    /** What the route saw when it became ready. */
    private class OpenedRoute {
        var readyAt: Long? = null
        var firstValue: NostrEventCardState? = null
    }

    /**
     * Prepares a first page whose only message names a card and is still being hydrated, against a cache that answers
     * after [cacheReadMillis]. On readiness a resolver is created over the same snapshots, as the conversation screen
     * does, and its first value is captured.
     */
    private fun TestScope.openFirstPage(cacheReadMillis: Long): OpenedRoute {
        val opened = OpenedRoute()
        val snapshots = NostrEventCardSnapshots()
        val note = noteBech32(EVENT_ID)
        val raw = eventCardTimelineRecord("m", "nostr:$note")
        val hydrated = CompletableDeferred<List<TimelineMessageRecordFfi>>()
        val coordinator =
            ConversationInitialPresentationWarmCoordinator(
                scope = backgroundScope,
                warmProfiles = { awaitProfileRead() },
                warmEventCards = { work ->
                    warmInitialEventCardSnapshots(work, snapshots) { references ->
                        delay(cacheReadMillis)
                        references.map { CACHED }
                    }
                },
                onReady = {
                    opened.readyAt = currentTime
                    val resolver =
                        NostrEventCardResolver(
                            parentScope = backgroundScope,
                            readCached = { references -> references.map { null } },
                            refresh = { null },
                            snapshots = snapshots,
                        )
                    opened.firstValue = resolver.state(NostrEventReference.Event(EVENT_ID), note).value
                },
            )
        coordinator.prepare(
            InitialPresentationWarmWork(
                senders = listOf("sender"),
                eventCardRecords = listOf(raw),
                hydratedRecords = hydrated,
            ),
        )
        // The page's own Markdown hydration is the only parse, and the warm reads its result.
        backgroundScope.launch {
            delay(HYDRATION_MILLIS)
            hydrated.complete(listOf(raw.withMarkdownTokens(eventCardDocument(note))))
        }
        runCurrent()
        return opened
    }

    /** A profile read slow enough that its budget, not its result, ends it. */
    private suspend fun awaitProfileRead() = delay(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS * 10)

    private companion object {
        val EVENT_ID = "a".repeat(64)
        const val HYDRATION_MILLIS = 20L

        /** Slower than the 100 ms profile budget, as the first read after a cold start can be. */
        const val COLD_CACHE_READ_MILLIS = 400L

        val CACHED =
            NostrEventCardState.Loaded(
                NostrEvent(
                    id = EVENT_ID,
                    pubkey = "c".repeat(64),
                    createdAt = 1,
                    kind = 1,
                    tags = emptyList(),
                    content = "Seen before",
                    sig = "0".repeat(128),
                ).toCardModel(),
            )
    }
}
