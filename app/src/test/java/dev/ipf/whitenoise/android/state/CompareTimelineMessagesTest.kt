package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class CompareTimelineMessagesTest {
    private fun msg(
        id: String,
        recordedAt: ULong,
        order: ULong,
        authoritativeOrder: ULong? = null,
    ) = TimelineMessage(
        id = id,
        record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "g",
                sender = "s",
                plaintext = "",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = recordedAt,
                receivedAt = recordedAt,
            ),
        status = MessageStatus.Received,
        timelineOrder = order,
        authoritativeOrder = authoritativeOrder,
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

    @Test
    fun displayOrderPreservesAuthoritativeRowsAndKeepsOptimisticSendAtLiveHead() {
        val system = msg("system", recordedAt = 200uL, order = 0uL, authoritativeOrder = 0uL)
        val app = msg("app", recordedAt = 100uL, order = 0uL, authoritativeOrder = 1uL)
        val optimistic = msg("optimistic", recordedAt = 300uL, order = 1uL)

        assertEquals(
            listOf("system", "app", "optimistic"),
            orderTimelineMessagesForDisplay(listOf(app, optimistic, system)).map { it.id },
        )
    }

    @Test
    fun displayOrderMergesTransientPositionBridgeWithoutReorderingAuthoritativeRows() {
        val first = msg("first", recordedAt = 100uL, order = 0uL, authoritativeOrder = 0uL)
        val second = msg("second", recordedAt = 300uL, order = 0uL, authoritativeOrder = 1uL)
        val bridge = msg("bridge", recordedAt = 200uL, order = 1uL)

        assertEquals(
            listOf("first", "bridge", "second"),
            orderTimelineMessagesForDisplay(listOf(second, bridge, first)).map { it.id },
        )
    }
}
