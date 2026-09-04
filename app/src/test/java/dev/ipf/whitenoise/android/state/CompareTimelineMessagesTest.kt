package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class CompareTimelineMessagesTest {
    /** Builds the smallest display row needed to exercise ordering policy. */
    private fun msg(
        id: String,
        recordedAt: ULong,
        order: ULong,
        authoritativeOrder: ULong? = null,
        displayAfterMessageIdHex: String? = null,
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
        displayAfterMessageIdHex = displayAfterMessageIdHex,
    )

    /** A total fallback comparator produces the same result for every input permutation. */
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

    /** MDK-ranked rows stay fixed while a newer optimistic send remains at the head. */
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

    /** A one-frame optimistic bridge does not reorder the authoritative rows around it. */
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

    /** A wall-time inversion cannot move an unrelated overlay ahead of the MDK range. */
    @Test
    fun displayOrderKeepsOverlayBehindInvertedAuthoritativeRange() {
        val mdkFirst = msg("mdk-first", recordedAt = 100uL, order = 0uL, authoritativeOrder = 0uL)
        val mdkSecond = msg("mdk-second", recordedAt = 50uL, order = 0uL, authoritativeOrder = 1uL)
        val overlay = msg("overlay", recordedAt = 75uL, order = 0uL)

        assertEquals(
            listOf("mdk-first", "mdk-second", "overlay"),
            orderTimelineMessagesForDisplay(listOf(overlay, mdkSecond, mdkFirst)).map { it.id },
        )
    }

    /** Durable stream children stay under their prompt instead of crossing an MDK-ranked event. */
    @Test
    fun displayOrderAnchorsDurableStreamChainBelowAuthoritativePrompt() {
        val membership = msg("membership", recordedAt = 200uL, order = 0uL, authoritativeOrder = 0uL)
        val prompt = msg("prompt", recordedAt = 100uL, order = 0uL, authoritativeOrder = 1uL)
        val start =
            msg(
                "start",
                recordedAt = 100uL,
                order = 1uL,
                displayAfterMessageIdHex = "prompt",
            )
        val final =
            msg(
                "final",
                recordedAt = 100uL,
                order = 2uL,
                displayAfterMessageIdHex = "start",
            )

        assertEquals(
            listOf("membership", "prompt", "start", "final"),
            orderTimelineMessagesForDisplay(listOf(final, prompt, membership, start)).map { it.id },
        )
    }
}
