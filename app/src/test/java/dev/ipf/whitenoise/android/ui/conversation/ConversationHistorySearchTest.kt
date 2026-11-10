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

    /** An available target is centered, then highlighted; the highlight's live region tells TalkBack. */
    @Test
    fun availableTargetIsCenteredThenHighlighted() =
        runBlocking {
            val steps = RecordingLanding()
            assertEquals(SearchTargetLanding.NAVIGATE, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "center", "highlight"), steps.calls)
        }

    /**
     * Centering records a new reading intent, which must not cancel the highlight of the landing that caused it:
     * only a newer navigation or gesture does. This is why landed results showed no highlight on a device.
     */
    @Test
    fun centeringsOwnIntentDoesNotCancelTheHighlight() =
        runBlocking {
            val steps = RecordingLanding(intentAdvancesAfter = "center")
            assertEquals(SearchTargetLanding.NAVIGATE, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "center", "highlight"), steps.calls)
        }

    /** A hidden target reports unavailable without loading its window or moving the reader. */
    @Test
    fun hiddenTargetIsUnavailableWithoutLoading() =
        runBlocking {
            val steps = RecordingLanding(hidden = true)
            assertEquals(SearchTargetLanding.UNAVAILABLE, landSearchResult(steps.steps()))
            assertEquals(listOf("unavailable"), steps.calls)
        }

    /** A loaded window whose target is gone puts the reader back before saying it is unavailable. */
    @Test
    fun goneTargetRestoresThePreviousPositionBeforeReporting() =
        runBlocking {
            val steps = RecordingLanding(target = record("g", "m", at = 10uL).copy(deleted = true))
            assertEquals(SearchTargetLanding.UNAVAILABLE, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "restore", "unavailable"), steps.calls)
        }

    /** A window that could not load restores the reader and asks for Retry instead of claiming the message is gone. */
    @Test
    fun retryableWindowOffersRetryNotUnavailable() =
        runBlocking {
            val steps = RecordingLanding(availability = MessageAvailability.RETRYABLE)
            assertEquals(SearchTargetLanding.RETRY, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "restore", "failed"), steps.calls)
        }

    /** A loaded target that could not be centered is offered for Retry instead of landing silently. */
    @Test
    fun centeringFailureOffersRetry() =
        runBlocking {
            val steps = RecordingLanding(centers = false)
            assertEquals(SearchTargetLanding.RETRY, landSearchResult(steps.steps()))
            assertEquals(listOf("load", "center", "failed"), steps.calls)
        }

    /** A newer tap or gesture during the window load supersedes it: nothing is reported, centered or highlighted. */
    @Test
    fun supersededLoadSettlesNothing() =
        runBlocking {
            val steps = RecordingLanding(supersedeAfter = "load")
            assertNull(landSearchResult(steps.steps()))
            assertEquals(listOf("load"), steps.calls)
        }

    /** A newer navigation during centering supersedes the landing before any highlight or report. */
    @Test
    fun supersededCenteringNeverHighlights() =
        runBlocking {
            val steps = RecordingLanding(supersedeAfter = "center")
            assertNull(landSearchResult(steps.steps()))
            assertEquals(listOf("load", "center"), steps.calls)
        }

    /** A failed search landing offers Retry, and a newer navigation or reader scroll withdraws it. */
    @Test
    fun aNewerNavigationWithdrawsTheRetryOfAFailedLanding() {
        val retry = SearchTargetRetryState()
        val owner = MessageTargetNavigationOwner(onOwnershipChanged = retry::clear)
        retry.failed(owner.begin())
        assertTrue(retry.pending)

        owner.begin()
        assertFalse("a reply, mention or search-step jump withdraws the Retry", retry.pending)

        retry.failed(owner.begin())
        owner.cancel()
        assertFalse("a reader scroll withdraws the Retry", retry.pending)
    }

    /** A landing superseded before it failed never offers a Retry. */
    @Test
    fun aLandingSupersededBeforeItFailedOffersNoRetry() {
        val retry = SearchTargetRetryState()
        val owner = MessageTargetNavigationOwner(onOwnershipChanged = retry::clear)
        val first = owner.begin()
        owner.cancel()

        retry.failed(first)

        assertFalse(retry.pending)
    }

    /** Retrying a still-current failure clears the chip and restarts the landing once. */
    @Test
    fun retryingACurrentFailureRestartsTheLanding() {
        val retry = SearchTargetRetryState()
        val owner = MessageTargetNavigationOwner(onOwnershipChanged = retry::clear)
        retry.failed(owner.begin())

        retry.retry()

        assertFalse(retry.pending)
        assertEquals(1L, retry.generation)
    }

    /** Only a chat-list search open lands as a search result; playback, speech and plain opens do not. */
    @Test
    fun onlySearchOpensLandAsSearchResults() {
        assertTrue(landsAsSearchResult("m", focusFromSearch = true, ttsFocusSessionId = null))
        val playback = landsAsSearchResult("m", focusFromSearch = false, ttsFocusSessionId = null)
        assertFalse("a playback destination", playback)
        assertFalse("a live speech return", landsAsSearchResult("m", focusFromSearch = false, ttsFocusSessionId = 3L))
        assertFalse("no target", landsAsSearchResult(null, focusFromSearch = true, ttsFocusSessionId = null))
    }

    /** The landing decision for one combination. */
    private fun landing(
        hidden: Boolean = false,
        availability: MessageAvailability?,
        target: TimelineMessageRecordFfi?,
    ) = searchTargetLanding(hidden, availability, target, nowMillis = 1_000_000L)

    /**
     * Records each landing step. [supersedeAfter] ends navigation ownership after the named step, while
     * [intentAdvancesAfter] only advances the reading intent, as centering itself does.
     */
    private class RecordingLanding(
        val hidden: Boolean = false,
        val availability: MessageAvailability = MessageAvailability.AVAILABLE,
        val target: TimelineMessageRecordFfi = record("g", "m", at = 10uL),
        val centers: Boolean = true,
        val supersedeAfter: String? = null,
        val intentAdvancesAfter: String? = null,
    ) {
        val calls = mutableListOf<String>()
        private var owned = true
        private var intentCurrent = true

        /** Records [step] and applies any supersession that follows it. */
        private fun step(step: String) {
            calls += step
            if (step == supersedeAfter) owned = false
            if (step == intentAdvancesAfter) intentCurrent = false
        }

        /** The injected steps. */
        fun steps() =
            SearchResultLandingSteps(
                isCurrent = { owned && intentCurrent },
                ownsNavigation = { owned },
                hidden = { hidden },
                loadWindow = {
                    step("load")
                    availability
                },
                loadedRecord = { target },
                center = {
                    step("center")
                    centers
                },
                highlight = { step("highlight") },
                unavailable = { step("unavailable") },
                failed = { step("failed") },
                restoreAnchor = { step("restore") },
                nowMillis = { 1_000_000L },
            )
    }
}
