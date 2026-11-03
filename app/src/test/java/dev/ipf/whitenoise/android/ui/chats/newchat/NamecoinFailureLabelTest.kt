package dev.ipf.whitenoise.android.ui.chats.newchat

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.namecoin.NamecoinResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamecoinFailureLabelTest {
    @Test
    fun notFoundUsesResolveFailureLabel() {
        assertEquals(
            R.string.namecoin_resolve_failed,
            namecoinFailureLabel(NamecoinResolver.NamecoinResult.NotFound),
        )
    }

    @Test
    fun unavailableUsesResolversUnavailableLabel() {
        assertEquals(
            R.string.namecoin_resolvers_unavailable,
            namecoinFailureLabel(NamecoinResolver.NamecoinResult.Unavailable),
        )
    }

    @Test
    fun resolvedHasNoFailureLabel() {
        val resolved =
            NamecoinResolver.NamecoinResult.Resolved(
                pubkeyHex = "a".repeat(64),
                relays = emptyList(),
                namecoinName = "d/example",
                localPart = "_",
            )
        assertNull(namecoinFailureLabel(resolved))
    }
}
