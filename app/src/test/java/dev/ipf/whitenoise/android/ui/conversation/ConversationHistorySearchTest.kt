package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
