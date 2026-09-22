package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Title precedence for the conversation header: private nickname, prepared title, own projection. */
class ConversationHeaderTitleTest {
    /** A DM with a nickname shows it instead of the engine's prepared network name. */
    @Test
    fun directMessageWithNicknamePrefersTheNickname() {
        assertEquals(
            "Alex Cousin",
            conversationHeaderTitle(
                dmPeerAccountIdHex = PEER,
                contactNickname = { if (it == PEER) "Alex Cousin" else null },
                preparedTitle = "alex@relay.example",
                projectedTitle = { FALLBACK },
            ),
        )
    }

    /** A DM without a nickname keeps the prepared title. */
    @Test
    fun directMessageWithoutNicknameKeepsThePreparedTitle() {
        assertEquals(
            "alex@relay.example",
            conversationHeaderTitle(
                dmPeerAccountIdHex = PEER,
                contactNickname = { null },
                preparedTitle = "alex@relay.example",
                projectedTitle = { FALLBACK },
            ),
        )
    }

    /** With no nickname and no prepared title, the app's own projection ends at the short identifier. */
    @Test
    fun directMessageWithoutPreparedTitleFallsBackToTheProjection() {
        assertEquals(
            FALLBACK,
            conversationHeaderTitle(
                dmPeerAccountIdHex = PEER,
                contactNickname = { null },
                preparedTitle = null,
                projectedTitle = { FALLBACK },
            ),
        )
    }

    /** A group carries no nickname peer, so its prepared title is never replaced by a member label. */
    @Test
    fun groupKeepsItsPreparedTitle() {
        assertEquals(
            "Book club",
            conversationHeaderTitle(
                dmPeerAccountIdHex = null,
                contactNickname = { error("groups must not consult contact nicknames") },
                preparedTitle = "Book club",
                projectedTitle = { FALLBACK },
            ),
        )
    }

    /** A cleared nickname sanitizes to null and hands the title back to the prepared name. */
    @Test
    fun blankNicknameFallsBackInsteadOfShowingEmptyText() {
        assertEquals(
            "alex@relay.example",
            conversationHeaderTitle(
                dmPeerAccountIdHex = PEER,
                contactNickname = { "   " },
                preparedTitle = "alex@relay.example",
                projectedTitle = { FALLBACK },
            ),
        )
    }

    /** A nickname carrying spoofing characters is sanitized like every other displayed name. */
    @Test
    fun nicknameIsSanitizedBeforeDisplay() {
        assertEquals(
            "Alex",
            conversationHeaderTitle(
                dmPeerAccountIdHex = PEER,
                contactNickname = { "‮Alex" },
                preparedTitle = "alex@relay.example",
                projectedTitle = { FALLBACK },
            ),
        )
    }

    /** A blank peer reference never reaches the nickname store, which is keyed by a real pubkey. */
    @Test
    fun blankPeerReferenceSkipsTheNicknameLookup() {
        assertEquals(
            "alex@relay.example",
            conversationHeaderTitle(
                dmPeerAccountIdHex = "  ",
                contactNickname = { error("a blank peer must not be looked up") },
                preparedTitle = "alex@relay.example",
                projectedTitle = { FALLBACK },
            ),
        )
    }

    private companion object {
        const val PEER = "02"
        const val FALLBACK = "npub1abc…xyz"
    }
}
