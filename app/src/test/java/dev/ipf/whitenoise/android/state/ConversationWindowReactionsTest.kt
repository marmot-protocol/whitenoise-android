package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ConversationReactionFfi
import dev.ipf.marmotkit.ConversationReactionsFfi
import dev.ipf.whitenoise.android.core.ReactionTally
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationWindowReactionsTest {
    private val reactions =
        ConversationReactionsFfi(
            totalCount = 5uL,
            totalKinds = 2uL,
            items =
                listOf(
                    ConversationReactionFfi(
                        "👍",
                        3uL,
                        listOf("a", "b"),
                        viewerReacted = false,
                        reactionMessageIdHex = null,
                    ),
                    ConversationReactionFfi(
                        "❤️",
                        2uL,
                        listOf("me", "c"),
                        viewerReacted = true,
                        reactionMessageIdHex = null,
                    ),
                ),
            omittedKinds = 0uL,
        )

    /** Counts come from MDK even when the reactor preview is shorter, and the viewer flag marks mine. */
    @Test
    fun talliesUseCompleteCountsAndViewerFlag() {
        assertEquals(
            listOf(ReactionTally("👍", 3, mine = false), ReactionTally("❤️", 2, mine = true)),
            windowReactionTallies(reactions, emptyList()),
        )
    }

    /** An optimistic add or remove adjusts the count and the viewer flag until MDK echoes it. */
    @Test
    fun optimisticChangesAdjustTallies() {
        val changed =
            windowReactionTallies(
                reactions,
                listOf(
                    OptimisticReactionChange("m", "👍", add = true),
                    OptimisticReactionChange("m", "❤️", add = false),
                ),
            )
        assertEquals(listOf(ReactionTally("👍", 4, mine = true), ReactionTally("❤️", 1, mine = false)), changed)
    }

    /** Removing the only reaction of a kind drops the chip; a duplicate add is not double counted. */
    @Test
    fun optimisticEdgeCases() {
        val fire = ConversationReactionFfi("🔥", 1uL, listOf("me"), true, null)
        val single = ConversationReactionsFfi(1uL, 1uL, listOf(fire), 0uL)
        val removed = windowReactionTallies(single, listOf(OptimisticReactionChange("m", "🔥", add = false)))
        assertEquals(emptyList<ReactionTally>(), removed)
        assertEquals(
            listOf(ReactionTally("🔥", 1, mine = true)),
            windowReactionTallies(single, listOf(OptimisticReactionChange("m", "🔥", add = true))),
        )
    }

    /** Participants come from the bounded preview, with the viewer's optimistic change applied. */
    @Test
    fun participantsFollowPreviewAndOptimisticChanges() {
        val participants =
            windowReactionParticipants(reactions, "me", listOf(OptimisticReactionChange("m", "❤️", add = false)))
        assertEquals(listOf("a" to "👍", "b" to "👍", "c" to "❤️"), participants.map { it.sender to it.emoji })
    }

    /** Window references win for retained messages; sender-based tallies still serve everything else. */
    @Test
    fun windowTalliesMergeWithSenderTallies() {
        val references = mapOf("m" to referencesFor(reactions))
        val senders = mapOf("optimistic" to mapOf("👍" to setOf("me")), "m" to mapOf("👍" to setOf("ignored")))
        val merged = reactionTalliesForWindow(senders, references, emptyList(), "me")
        assertEquals(3, merged.getValue("m").first { it.emoji == "👍" }.count)
        assertEquals(listOf(ReactionTally("👍", 1, mine = true)), merged.getValue("optimistic"))
    }

    @Test
    fun changesStayWithTheirTarget() {
        val references = mapOf("first" to referencesFor(reactions), "second" to referencesFor(reactions))
        val merged =
            reactionTalliesForWindow(
                emptyMap(),
                references,
                listOf(
                    OptimisticReactionChange("first", "👍", add = true),
                    OptimisticReactionChange("second", "❤️", add = false),
                    OptimisticReactionChange("outside", "🔥", add = true),
                ),
                "me",
            )
        assertEquals(4, merged.getValue("first").first { it.emoji == "👍" }.count)
        assertEquals(2, merged.getValue("first").first { it.emoji == "❤️" }.count)
        assertEquals(3, merged.getValue("second").first { it.emoji == "👍" }.count)
        assertEquals(1, merged.getValue("second").first { it.emoji == "❤️" }.count)
        assertEquals(setOf("first", "second"), merged.keys)
    }

    /** One `:party:` chip as MDK reports it, naming [reactionMessageIdHex] as its artwork event. */
    private fun party(
        reactionMessageIdHex: String?,
        count: ULong = 1uL,
        viewerReacted: Boolean = false,
    ) = ConversationReactionsFfi(
        count,
        1uL,
        listOf(ConversationReactionFfi(":party:", count, listOf("a"), viewerReacted, reactionMessageIdHex)),
        0uL,
    )

    /** A custom-emoji chip carries the reaction event MDK names, the key for its artwork lookup. */
    @Test
    fun talliesCarryTheReactionEventThatDefinesArtwork() {
        assertEquals(
            listOf(ReactionTally(":party:", 1, mine = false, reactionMessageIdHex = "r1")),
            windowReactionTallies(party("r1"), emptyList()),
        )
    }

    /** When the earliest reaction is withdrawn MDK names the next one, so the chip's lookup key moves. */
    @Test
    fun reactionUpdateMovesTheArtworkEventAndRemovalDropsTheChip() {
        val before = windowReactionTallies(party("r1", count = 2uL), emptyList())
        val after = windowReactionTallies(party("r2"), emptyList())
        assertEquals("r1", before.single().reactionMessageIdHex)
        assertEquals("r2", after.single().reactionMessageIdHex)
        assertEquals(
            emptyList<ReactionTally>(),
            windowReactionTallies(ConversationReactionsFfi(0uL, 0uL, emptyList(), 0uL), emptyList()),
        )
    }

    /** The viewer's optimistic add or removal keeps the artwork event of the chip it adjusts. */
    @Test
    fun optimisticChangesKeepTheArtworkEvent() {
        val added = windowReactionTallies(party("r1"), listOf(OptimisticReactionChange("m", ":party:", add = true)))
        assertEquals(ReactionTally(":party:", 2, mine = true, reactionMessageIdHex = "r1"), added.single())
        val removed =
            windowReactionTallies(
                party("r1", count = 2uL, viewerReacted = true),
                listOf(OptimisticReactionChange("m", ":party:", add = false)),
            )
        assertEquals("r1", removed.single().reactionMessageIdHex)
    }

    private fun referencesFor(reactions: ConversationReactionsFfi) =
        dev.ipf.marmotkit.ConversationMessageReferencesFfi(
            messageIdHex = "m",
            sender = null,
            replyAuthor = null,
            mentions = emptyList(),
            mentionsTruncated = false,
            replyMentions = emptyList(),
            replyMentionsTruncated = false,
            system = null,
            reactions = reactions,
        )
}
