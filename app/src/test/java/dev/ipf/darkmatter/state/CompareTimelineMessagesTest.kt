package dev.ipf.darkmatter.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class CompareTimelineMessagesTest {
    private fun msg(
        id: String,
        recordedAt: ULong,
        order: ULong,
    ) = TimelineMessage(
        id = id,
        record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "g",
                sender = "s",
                plaintext = "",
                contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
                kind = 9uL,
                tags = emptyList(),
                recordedAt = recordedAt,
                receivedAt = recordedAt,
            ),
        status = MessageStatus.Received,
        timelineOrder = order,
    )

    @Test
    fun sortResultIsIndependentOfInputOrder() {
        // compareTimelineMessages breaks every tie on the unique id, so it is a
        // total order: a list sorts to the same sequence regardless of starting
        // order. ConversationController relies on this to keep `timelineOrder`
        // as an unordered membership set (publishTimelineFromIndexes re-sorts),
        // which is what lets insertTimelineItemId append in O(1). See #74.
        val a = msg("a", recordedAt = 100uL, order = 1uL)
        val b = msg("b", recordedAt = 100uL, order = 1uL) // tie with a on time+order → id wins
        val c = msg("c", recordedAt = 50uL, order = 5uL)
        val d = msg("d", recordedAt = 200uL, order = 0uL)

        val expected = listOf("c", "a", "b", "d")
        listOf(
            listOf(a, b, c, d),
            listOf(d, c, b, a),
            listOf(b, d, a, c),
            listOf(c, a, d, b),
        ).forEach { permutation ->
            assertEquals(expected, permutation.sortedWith(::compareTimelineMessages).map { it.id })
        }
    }
}
