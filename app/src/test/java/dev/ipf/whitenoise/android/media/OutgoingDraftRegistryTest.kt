package dev.ipf.whitenoise.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-wide draft registry (#3373): every lookup for one account and conversation shares its drafts, so a
 * replaced controller or screen keeps every pick's draft, and an ended send retires into its draft's own domain.
 */
class OutgoingDraftRegistryTest {
    private val retired = mutableListOf<MediaTrustDomain>()
    private val registry = OutgoingDraftRegistry { retired += it }

    /** Lookups for one conversation share its drafts, while another conversation or account never does. */
    @Test
    fun lookupsForOneConversationShareItsDrafts() {
        val first = registry.domain(ACCOUNT, GROUP, "slot-a")
        assertTrue(first is MediaTrustDomain.Outgoing)
        assertEquals(first, registry.domain(ACCOUNT, GROUP, "slot-b"))
        assertNotEquals(first, registry.domain(ACCOUNT, "other-group", "slot-a"))
        assertNotEquals(first, registry.domain("other-account", GROUP, "slot-a"))
        assertEquals(MediaTrustDomain.Unique, registry.domain(null, GROUP, "slot-a"))
        assertEquals(MediaTrustDomain.Unique, registry.domain(ACCOUNT, GROUP, null))
    }

    /** A sent pick keeps its domain after the composer moves on, and its draft retires into that domain. */
    @Test
    fun anEndedSendRetiresItsDraftsDomain() {
        val sent = registry.domain(ACCOUNT, GROUP, "slot-a")
        registry.retain(ACCOUNT, GROUP, setOf("slot-a"))
        val send = registry.beginSend(ACCOUNT, GROUP, listOf("slot-a"))
        registry.retain(ACCOUNT, GROUP, emptySet())
        assertNotEquals(sent, registry.domain(ACCOUNT, GROUP, "slot-b"))
        assertEquals(sent, registry.domain(ACCOUNT, GROUP, "slot-a"))
        registry.endSend(send)
        assertEquals(listOf(sent), retired)
    }

    private companion object {
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
