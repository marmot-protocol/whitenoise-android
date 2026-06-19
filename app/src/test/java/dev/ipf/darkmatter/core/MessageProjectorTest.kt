package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageProjectorTest {
    @Test
    fun reactionTalliesNetAddsAndDeleteTombstonesBySender() {
        val records =
            listOf(
                reaction("r1", sender = "alice", target = "m1", emoji = "👍", at = 1u),
                reaction("r2", sender = "bob", target = "m1", emoji = "👍", at = 2u),
                delete("d1", sender = "alice", target = "r1", at = 3u),
                reaction("r4", sender = "alice", target = "m1", emoji = "❤️", at = 4u),
            )

        val tallies = MessageProjector.reactionTallies(records, targetMessageId = "m1", myAccountId = "bob")

        assertEquals(
            listOf(
                ReactionTally(emoji = "👍", count = 1, mine = true),
                ReactionTally(emoji = "❤️", count = 1, mine = false),
            ),
            tallies,
        )
    }

    @Test
    fun reactionTalliesMatchSendersCaseInsensitively() {
        val records =
            listOf(
                reaction("r1", sender = "AB12CD", target = "m1", emoji = "👍", at = 1u),
                reaction("r2", sender = "ab12cd", target = "m1", emoji = "❤️", at = 2u),
                delete("d1", sender = "AB12CD", target = "m1", at = 3u),
                reaction("r4", sender = "AB12CD", target = "m1", emoji = "🔥", at = 4u),
            )

        val tallies = MessageProjector.reactionTallies(records, targetMessageId = "m1", myAccountId = "Ab12Cd")

        // The target-delete tombstone must clear both case spellings, and the
        // surviving reaction must read as mine despite the casing drift.
        assertEquals(listOf(ReactionTally(emoji = "🔥", count = 1, mine = true)), tallies)
    }

    @Test
    fun reactionTalliesKeepDuplicateReactionWhenOneIsRetracted() {
        val records =
            listOf(
                // alice double-taps the same emoji: two distinct reaction events.
                reaction("r1", sender = "alice", target = "m1", emoji = "👍", at = 1u),
                reaction("r2", sender = "alice", target = "m1", emoji = "👍", at = 2u),
                // She retracts only one of them (r1). r2 is still live, so the 👍
                // tally must stay at 1 instead of vanishing.
                delete("d1", sender = "alice", target = "r1", at = 3u),
            )

        val tallies = MessageProjector.reactionTallies(records, targetMessageId = "m1", myAccountId = "alice")

        assertEquals(listOf(ReactionTally(emoji = "👍", count = 1, mine = true)), tallies)
    }

    @Test
    fun reactionTalliesDropEmojiOnlyAfterAllDuplicateReactionsAreRetracted() {
        val records =
            listOf(
                reaction("r1", sender = "alice", target = "m1", emoji = "👍", at = 1u),
                reaction("r2", sender = "alice", target = "m1", emoji = "👍", at = 2u),
                // Retract both of alice's 👍 events; the emoji disappears entirely.
                delete("d1", sender = "alice", target = "r1", at = 3u),
                delete("d2", sender = "alice", target = "r2", at = 4u),
            )

        val tallies = MessageProjector.reactionTallies(records, targetMessageId = "m1", myAccountId = "alice")

        assertEquals(emptyList<ReactionTally>(), tallies)
    }

    @Test
    fun reactionTalliesMessageDeleteClearsEveryDuplicateFromTheSender() {
        val records =
            listOf(
                reaction("r1", sender = "alice", target = "m1", emoji = "👍", at = 1u),
                reaction("r2", sender = "alice", target = "m1", emoji = "👍", at = 2u),
                reaction("r3", sender = "bob", target = "m1", emoji = "👍", at = 3u),
                // alice deletes the reacted-to message itself: all of her reactions
                // on m1 are retracted, but bob's survives so 👍 stays at 1.
                delete("d1", sender = "alice", target = "m1", at = 4u),
            )

        val tallies = MessageProjector.reactionTallies(records, targetMessageId = "m1", myAccountId = "bob")

        assertEquals(listOf(ReactionTally(emoji = "👍", count = 1, mine = true)), tallies)
    }

    @Test
    fun reactionTalliesIgnoreAForgedDeleteFromAnotherAccount() {
        val records =
            listOf(
                reaction("r1", sender = "alice", target = "m1", emoji = "👍", at = 1u),
                // bob forges a delete of alice's reaction event — only alice may
                // retract it, so the tally must survive.
                delete("d1", sender = "bob", target = "r1", at = 2u),
            )

        val tallies = MessageProjector.reactionTallies(records, targetMessageId = "m1", myAccountId = "carol")

        assertEquals(listOf(ReactionTally(emoji = "👍", count = 1, mine = false)), tallies)
    }

    @Test
    fun displayBodyUsesTextAndMediaTagsFromCurrentBindings() {
        val reply =
            message(
                id = "reply",
                plaintext = "Visible reply",
                tags = listOf(eventTag("m0"), quoteTag("m0")),
            )
        val media =
            message(
                id = "media",
                plaintext = "Lab capture",
                tags =
                    listOf(
                        MessageTagFfi(
                            listOf(
                                "imeta",
                                "url https://example.invalid/scan.png",
                                "m image/png",
                                "filename scan.png",
                            ),
                        ),
                    ),
            )
        val mediaWithoutCaption =
            message(
                id = "media-no-caption",
                plaintext = "",
                tags = listOf(MessageTagFfi(listOf("imeta", "filename fallback.png"))),
            )

        assertEquals("Visible reply", MessageProjector.displayBody(reply))
        assertEquals("Lab capture", MessageProjector.displayBody(media))
        assertEquals("fallback.png", MessageProjector.displayBody(mediaWithoutCaption))
    }

    @Test
    fun previewTextRecognizesStreamStartAndFinalMessages() {
        val start =
            message(
                id = "start",
                plaintext = "",
                kind = 1200uL,
                tags = listOf(MessageProjector.streamTag("abc123")),
            )
        val final =
            message(
                id = "final",
                plaintext = "Final transcript",
                kind = 9uL,
                tags =
                    listOf(
                        MessageProjector.streamTag("abc123"),
                        MessageTagFfi(listOf("stream-start", "start")),
                    ),
            )

        assertEquals("Agent stream started", MessageProjector.previewText(start))
        assertEquals("Final transcript", MessageProjector.previewText(final))
        assertTrue(MessageProjector.isStreamFinal(final))
    }

    @Test
    fun messageFallbackCopyCanBeLocalizedByUi() {
        val copy =
            MessageTextCopy(
                reactedFormat = "R:%1\$s",
                reactionFallback = "target",
                deleted = "deleted",
                invalidated = "invalidated",
                agentStreamStarted = "started",
                streamFinished = "finished",
                mediaAttachment = "media",
                message = "message",
            )
        val reaction = reaction(id = "r1", sender = "alice", target = "m1", emoji = "", at = 1u)
        val delete = delete(id = "d1", sender = "alice", target = "m1", at = 2u)
        val blank = message(id = "blank", plaintext = "")

        assertEquals("R:target", MessageProjector.previewText(reaction, copy))
        assertEquals("deleted", MessageProjector.previewText(delete, copy))
        assertEquals("message", MessageProjector.previewText(blank, copy))
    }

    @Test
    fun outgoingAndDeletedPredicatesAreStable() {
        val sent = message(id = "m1", direction = "sent")
        val received = message(id = "m2", direction = "received")

        assertTrue(MessageProjector.isMine(sent, myAccountId = "me"))
        assertFalse(MessageProjector.isMine(received, myAccountId = "me"))
        assertTrue(MessageProjector.isDeleted("m1", setOf("m1")))
        assertFalse(MessageProjector.isDeleted("m2", setOf("m1")))
    }

    private fun reaction(
        id: String,
        sender: String,
        target: String,
        emoji: String,
        at: UInt,
    ) = message(
        id = id,
        sender = sender,
        plaintext = emoji,
        kind = 7uL,
        tags = listOf(eventTag(target)),
        at = at,
    )

    private fun delete(
        id: String,
        sender: String,
        target: String,
        at: UInt,
    ) = message(
        id = id,
        sender = sender,
        plaintext = "",
        kind = 5uL,
        tags = listOf(eventTag(target)),
        at = at,
    )

    private fun message(
        id: String,
        direction: String = "received",
        sender: String = "alice",
        plaintext: String = "hello",
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
        at: UInt = 1u,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = direction,
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(blocks = emptyList()),
        kind = kind,
        tags = tags,
        recordedAt = at.toULong(),
        receivedAt = at.toULong(),
    )

    private fun eventTag(target: String) = MessageProjector.eventTag(target)

    private fun quoteTag(target: String) = MessageProjector.quoteTag(target)
}
