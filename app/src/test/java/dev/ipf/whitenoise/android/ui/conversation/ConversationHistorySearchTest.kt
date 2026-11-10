package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.state.MessageAvailability
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistorySearchTest {
    @Test
    fun secondPageIsFetchedWithThePairedCursor() =
        runBlocking {
            val seenCursors = mutableListOf<Pair<ULong?, String?>>()
            val result =
                paginateHistoryMatches { before, beforeMessageId ->
                    seenCursors += before to beforeMessageId
                    when (beforeMessageId) {
                        null ->
                            HistoryScanPage(
                                matches = listOf(30uL to "c"),
                                oldest = 20uL to "b",
                                hasMoreBefore = true,
                            )
                        else ->
                            HistoryScanPage(
                                matches = listOf(10uL to "a"),
                                oldest = 5uL to "a",
                                hasMoreBefore = false,
                            )
                    }
                }
            // The regression: page two must carry both cursor halves. The engine
            // rejects a beforeMessageId without a before, so a null `before` here
            // is exactly the bug that silently capped the scan at page one.
            assertEquals(listOf<Pair<ULong?, String?>>(null to null, 20uL to "b"), seenCursors)
            // Both pages' matches survive, oldest-first.
            assertEquals(
                listOf(
                    ConversationSearchMatch(messageIdHex = "a", timelineAt = 10uL),
                    ConversationSearchMatch(messageIdHex = "c", timelineAt = 30uL),
                ),
                result,
            )
        }

    @Test
    fun cancellationPropagatesInsteadOfResolving() {
        var threw = false
        try {
            runBlocking {
                paginateHistoryMatches { _, _ -> throw CancellationException("superseded") }
            }
        } catch (_: CancellationException) {
            threw = true
        }
        assertTrue("a cancelled scan must not resolve to a publishable value", threw)
    }

    @Test
    fun failedPageReadReturnsNullSoTheCallerKeepsWindowMatches() =
        runBlocking {
            assertNull(paginateHistoryMatches { _, _ -> null })
        }

    @Test
    fun exhaustionStopsPagingWhenNoOlderRowsRemain() =
        runBlocking {
            var calls = 0
            val result =
                paginateHistoryMatches { _, _ ->
                    calls += 1
                    HistoryScanPage(matches = listOf(1uL to "x"), oldest = 1uL to "x", hasMoreBefore = false)
                }
            assertEquals(1, calls)
            assertEquals(listOf(ConversationSearchMatch(messageIdHex = "x", timelineAt = 1uL)), result)
        }

    @Test
    fun runawayCapReturnsNullInsteadOfPublishingPartialResults() =
        runBlocking {
            var calls = 0
            val result =
                paginateHistoryMatches(maxPages = 1) { _, _ ->
                    calls += 1
                    HistoryScanPage(matches = listOf(2uL to "partial"), oldest = 1uL to "cursor", hasMoreBefore = true)
                }
            assertEquals(1, calls)
            assertNull(result)
        }

    /** Loading never reads as final: a pending scan is LOADING and its window matches cannot be stepped (#2873). */
    @Test
    fun aPendingScanIsLoadingAndBlocksArrows() {
        val status = conversationSearchScanStatus(query = "hello", scanMatches = null, scanFailed = false)
        assertEquals(ConversationSearchScanStatus.LOADING, status)
        assertFalse("partial window matches are not steppable", status.allowsSearchSteps(matchCount = 3))
    }

    /** A completed scan is authoritative, even when it found nothing. */
    @Test
    fun aCompletedScanEnablesArrowsOnlyWithMatches() {
        val done = conversationSearchScanStatus("hello", scanMatches = emptyList(), scanFailed = false)
        assertEquals(ConversationSearchScanStatus.COMPLETE, done)
        assertFalse(done.allowsSearchSteps(matchCount = 0))
        assertTrue(done.allowsSearchSteps(matchCount = 2))
    }

    /** A failed scan is distinct from loading, and a blank query is idle whatever a stale scan held. */
    @Test
    fun failedAndIdleScansAreDistinct() {
        val failed = conversationSearchScanStatus("hello", scanMatches = null, scanFailed = true)
        assertEquals(ConversationSearchScanStatus.FAILED, failed)
        assertTrue("loaded-only matches stay steppable once labelled", failed.allowsSearchSteps(matchCount = 1))
        val match = ConversationSearchMatch(messageIdHex = "a", timelineAt = 1uL)
        assertEquals(
            ConversationSearchScanStatus.IDLE,
            conversationSearchScanStatus(" ", scanMatches = listOf(match), scanFailed = true),
        )
    }

    /** A global-search target lands only when its exact message loaded and may still be shown (#1945). */
    @Test
    fun searchTargetLandingIsTruthfulAboutUnavailableAndRetryableTargets() {
        val live = record("g", "m", at = 10uL)
        assertEquals(SearchTargetLanding.NAVIGATE, landing(availability = MessageAvailability.AVAILABLE, target = live))
        assertEquals(SearchTargetLanding.UNAVAILABLE, landing(hidden = true, availability = null, target = live))
        val missing = landing(availability = MessageAvailability.MISSING, target = null)
        assertEquals(SearchTargetLanding.UNAVAILABLE, missing)
        assertEquals(SearchTargetLanding.RETRY, landing(availability = MessageAvailability.RETRYABLE, target = null))
        listOf(
            live.copy(deleted = true),
            live.copy(invalidationStatus = "LosingBranch"),
            record("g", "m", at = 10uL, expiresAt = 1uL),
        ).forEach { gone ->
            val landed = landing(availability = MessageAvailability.AVAILABLE, target = gone)
            assertEquals(SearchTargetLanding.UNAVAILABLE, landed)
        }
    }

    /** An available target is centered, then announced, then highlighted, in that order. */
    @Test
    fun availableTargetIsCenteredAnnouncedThenHighlighted() =
        runBlocking {
            val steps = RecordingLanding()
            assertEquals(SearchTargetLanding.NAVIGATE, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "center", "announce", "highlight"), steps.calls)
        }

    /** A hidden target reports unavailable without loading its window or showing anything. */
    @Test
    fun hiddenTargetIsUnavailableWithoutLoading() =
        runBlocking {
            val steps = RecordingLanding(hidden = true)
            assertEquals(SearchTargetLanding.UNAVAILABLE, landSearchResult(steps.steps()))
            assertEquals(listOf("unavailable"), steps.calls)
        }

    /** A window that could not load asks for Retry instead of claiming the message is gone. */
    @Test
    fun retryableWindowOffersRetryNotUnavailable() =
        runBlocking {
            val steps = RecordingLanding(availability = MessageAvailability.RETRYABLE)
            assertEquals(SearchTargetLanding.RETRY, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "failed"), steps.calls)
        }

    /** A newer tap or gesture during the window load supersedes it: nothing is reported, centered or highlighted. */
    @Test
    fun supersededLoadSettlesNothing() =
        runBlocking {
            val steps = RecordingLanding(supersedeAfter = "load")
            assertNull(landSearchResult(steps.steps()))
            assertEquals(listOf("load"), steps.calls)
        }

    /** A gesture during centering supersedes the landing before any announcement or highlight. */
    @Test
    fun supersededCenteringNeverAnnouncesOrHighlights() =
        runBlocking {
            val steps = RecordingLanding(supersedeAfter = "center")
            assertNull(landSearchResult(steps.steps()))
            assertEquals(listOf("load", "center"), steps.calls)
        }

    /** The search-result Retry is offered only for a current request and cleared by a newer navigation. */
    @Test
    fun searchTargetRetryIsPendingOnlyForTheCurrentRequest() {
        val owner = MessageTargetNavigationOwner()
        val retry = SearchTargetRetryState()
        val first = owner.begin()
        retry.failed(first)
        assertTrue(retry.pending)
        retry.retry()
        assertFalse(retry.pending)
        assertEquals(1L, retry.generation)

        val stale = owner.begin()
        owner.begin()
        retry.failed(stale)
        assertFalse("a superseded request offers no Retry", retry.pending)
    }

    /** The landing decision for one combination. */
    private fun landing(
        hidden: Boolean = false,
        availability: MessageAvailability?,
        target: TimelineMessageRecordFfi?,
    ) = searchTargetLanding(hidden, availability, target, nowMillis = 1_000_000L)

    /** Records each landing step and can supersede the landing right after a named step. */
    private class RecordingLanding(
        val hidden: Boolean = false,
        val availability: MessageAvailability = MessageAvailability.AVAILABLE,
        val supersedeAfter: String? = null,
    ) {
        val calls = mutableListOf<String>()
        private var current = true

        /** Records [step] and ends currency if it is the one to supersede after. */
        private fun step(step: String) {
            calls += step
            if (step == supersedeAfter) current = false
        }

        /** The injected steps. */
        fun steps() =
            SearchResultLandingSteps(
                isCurrent = { current },
                hidden = { hidden },
                loadWindow = {
                    step("load")
                    availability
                },
                loadedRecord = { record("g", "m", at = 10uL) },
                center = {
                    step("center")
                    true
                },
                announce = { step("announce") },
                highlight = { step("highlight") },
                unavailable = { step("unavailable") },
                failed = { step("failed") },
                nowMillis = { 1_000_000L },
            )
    }
}
