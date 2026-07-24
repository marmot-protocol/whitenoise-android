package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMessageSearchTest {
    @Test
    fun matchOnOlderPageIsReturnedWithoutLoadingItIntoTheConversationTimeline() =
        runTest {
            val reader =
                FakeConversationSearchTimelineReader(
                    mutableListOf(
                        page(
                            records = listOf(record("new-excluded", "marmot reaction", kind = 7uL, timelineAt = 30uL)),
                            hasMoreBefore = true,
                        ),
                        page(
                            records = listOf(record("old-match", "the alpine marmot", timelineAt = 10uL)),
                            hasMoreBefore = false,
                        ),
                    ),
                )

            val matches =
                ConversationMessageSearch.findMatches(
                    timelineReader = reader,
                    accountRef = "account",
                    groupIdHex = "group",
                    rawQuery = "MARMOT",
                    pageSize = 1u,
                )

            assertEquals(listOf("old-match"), matches.map { it.messageIdHex })
            assertEquals(listOf(null, 30uL), reader.queries.map { it.before })
            assertEquals(listOf(null, "new-excluded"), reader.queries.map { it.beforeMessageId })
            assertEquals(listOf("MARMOT", "MARMOT"), reader.queries.map { it.search })
            assertEquals(listOf(1u, 1u), reader.queries.map { it.limit })
        }

    @Test
    fun everyPageContributesEligibleMatchesInConversationOrder() =
        runTest {
            val reader =
                FakeConversationSearchTimelineReader(
                    mutableListOf(
                        page(
                            records =
                                listOf(
                                    record("new-match", "Marmot newest", timelineAt = 50uL),
                                    record("deleted", "Marmot deleted", timelineAt = 40uL, deleted = true),
                                ),
                            hasMoreBefore = true,
                        ),
                        page(
                            records =
                                listOf(
                                    record("middle-match", "middle marmot", kind = 1209uL, timelineAt = 30uL),
                                    record("system", "marmot system", kind = 1210uL, timelineAt = 20uL),
                                ),
                            hasMoreBefore = true,
                        ),
                        page(
                            records = listOf(record("old-match", "old marmot", kind = 1uL, timelineAt = 10uL)),
                            hasMoreBefore = false,
                        ),
                    ),
                )

            val matches =
                ConversationMessageSearch.findMatches(
                    timelineReader = reader,
                    accountRef = "account",
                    groupIdHex = "group",
                    rawQuery = "marmot",
                    pageSize = 2u,
                )

            assertEquals(
                listOf("old-match", "middle-match", "new-match"),
                matches.map { it.messageIdHex },
            )
            assertEquals(listOf(null, 40uL, 20uL), reader.queries.map { it.before })
            assertEquals(listOf(null, "deleted", "system"), reader.queries.map { it.beforeMessageId })
        }

    @Test
    fun noEligibleBodyMatchReturnsEmptyAfterExhaustingTheStore() =
        runTest {
            val reader =
                FakeConversationSearchTimelineReader(
                    mutableListOf(
                        page(
                            records = listOf(record("reaction", "marmot", kind = 7uL, timelineAt = 20uL)),
                            hasMoreBefore = true,
                        ),
                        page(
                            records = listOf(record("other", "not the needle", timelineAt = 10uL)),
                            hasMoreBefore = false,
                        ),
                    ),
                )

            val matches =
                ConversationMessageSearch.findMatches(
                    timelineReader = reader,
                    accountRef = "account",
                    groupIdHex = "group",
                    rawQuery = "marmot",
                )

            assertEquals(emptyList<String>(), matches)
            assertEquals(2, reader.queries.size)
        }

    @Test
    fun cancellationStopsAnExhaustiveScanBeforeAnotherPageCanPublish() =
        runTest {
            val secondReadStarted = CompletableDeferred<Unit>()
            var readCount = 0
            var secondReadCancelled = false
            val reader =
                object : ConversationMessageSearchTimelineReader {
                    override suspend fun timelineMessages(
                        accountRef: String,
                        query: TimelineMessageQueryFfi,
                    ): TimelinePageFfi {
                        readCount += 1
                        if (readCount == 1) {
                            return page(
                                records = listOf(record("new", "marmot", timelineAt = 20uL)),
                                hasMoreBefore = true,
                            )
                        }
                        secondReadStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            secondReadCancelled = true
                        }
                    }
                }

            val searchJob =
                launch {
                    ConversationMessageSearch.findMatches(
                        timelineReader = reader,
                        accountRef = "account",
                        groupIdHex = "group",
                        rawQuery = "marmot",
                    )
                }
            secondReadStarted.await()
            searchJob.cancelAndJoin()

            assertEquals(2, readCount)
            assertTrue("the in-flight page read must be cancelled", secondReadCancelled)
        }
}

private class FakeConversationSearchTimelineReader(
    private val pages: MutableList<TimelinePageFfi>,
) : ConversationMessageSearchTimelineReader {
    val queries = mutableListOf<TimelineMessageQueryFfi>()

    override suspend fun timelineMessages(
        accountRef: String,
        query: TimelineMessageQueryFfi,
    ): TimelinePageFfi {
        queries += query
        return pages.removeFirst()
    }
}

private fun page(
    records: List<TimelineMessageRecordFfi>,
    hasMoreBefore: Boolean,
) = TimelinePageFfi(
    messages = records,
    hasMoreBefore = hasMoreBefore,
    hasMoreAfter = false,
)

private fun record(
    id: String,
    plaintext: String,
    kind: ULong = 9uL,
    timelineAt: ULong,
    deleted: Boolean = false,
) = TimelineMessageRecordFfi(
    messageIdHex = id,
    sourceMessageIdHex = null,
    direction = "received",
    groupIdHex = "group",
    sender = "sender",
    plaintext = plaintext,
    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList()),
    kind = kind,
    tags = emptyList<MessageTagFfi>(),
    timelineAt = timelineAt,
    receivedAt = timelineAt,
    replyToMessageIdHex = null,
    replyPreview = null,
    mediaJson = null,
    media = emptyList(),
    agentTextStreamJson = null,
    groupSystem = null,
    reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
    deleted = deleted,
    deletedByMessageIdHex = null,
    invalidationStatus = null,
)
