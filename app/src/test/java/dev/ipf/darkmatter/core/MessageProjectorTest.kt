package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.marmotprotocol.marmotkit.AppMessagePayloadFfi
import org.marmotprotocol.marmotkit.AppMessageRecordFfi

class MessageProjectorTest {
    @Test
    fun reactionTalliesNetAddsAndRemovalsBySender() {
        val records = listOf(
            reaction("r1", sender = "alice", target = "m1", emoji = "👍", removed = false, at = 1u),
            reaction("r2", sender = "bob", target = "m1", emoji = "👍", removed = false, at = 2u),
            reaction("r3", sender = "alice", target = "m1", emoji = "", removed = true, at = 3u),
            reaction("r4", sender = "alice", target = "m1", emoji = "❤️", removed = false, at = 4u),
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
    fun displayBodyUsesStructuredReplyTextAndMediaCaption() {
        val reply = message(
            id = "reply",
            plaintext = """{"kind":"reply"}""",
            payload = AppMessagePayloadFfi.Reply(targetMessageId = "m0", text = "Visible reply"),
        )
        val media = message(
            id = "media",
            plaintext = "",
            payload = AppMessagePayloadFfi.Media(
                fileName = "scan.png",
                mediaType = "image/png",
                sizeBytes = 2048u,
                caption = "Lab capture",
            ),
        )

        assertEquals("Visible reply", MessageProjector.displayBody(reply))
        assertEquals("Lab capture", MessageProjector.displayBody(media))
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
        removed: Boolean,
        at: UInt,
    ) = message(
        id = id,
        sender = sender,
        payload = AppMessagePayloadFfi.Reaction(
            targetMessageId = target,
            emoji = emoji,
            removed = removed,
        ),
        at = at,
    )

    private fun message(
        id: String,
        direction: String = "received",
        sender: String = "alice",
        plaintext: String = "hello",
        payload: AppMessagePayloadFfi? = null,
        at: UInt = 1u,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = direction,
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        appMessage = payload,
        recordedAt = at.toULong(),
        receivedAt = at.toULong(),
    )
}
