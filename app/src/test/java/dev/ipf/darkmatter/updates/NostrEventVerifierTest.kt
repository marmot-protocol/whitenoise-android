package dev.ipf.darkmatter.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventVerifierTest {
    @Test
    fun verifiesBip340ReferenceVector() {
        assertTrue(
            BIP340.verify(
                publicKeyHex = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
                messageHex = "0000000000000000000000000000000000000000000000000000000000000000",
                signatureHex =
                    "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                        "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            ),
        )
    }

    @Test
    fun rejectsMutatedBip340Signature() {
        assertFalse(
            BIP340.verify(
                publicKeyHex = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
                messageHex = "0000000000000000000000000000000000000000000000000000000000000000",
                signatureHex =
                    "F907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                        "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            ),
        )
    }

    @Test
    fun canonicalEventIdUsesNostrSerializationOrder() {
        val event =
            NostrEvent(
                id = "0".repeat(64),
                pubkey = "1".repeat(64),
                createdAt = 123L,
                kind = 32267,
                tags = listOf(listOf("d", "org.parres.darkmatter"), listOf("a", "30063:${"1".repeat(64)}:org.parres.darkmatter@2026.6.20")),
                content = "",
                sig = "0".repeat(128),
            )
        assertEquals(
            "[0,\"${"1".repeat(64)}\",123,32267,[[\"d\",\"org.parres.darkmatter\"],[\"a\",\"30063:${"1".repeat(64)}:org.parres.darkmatter@2026.6.20\"]],\"\"]",
            event.canonicalJson(),
        )
    }
}
