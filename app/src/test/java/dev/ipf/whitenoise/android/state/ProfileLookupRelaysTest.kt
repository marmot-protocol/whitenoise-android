package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLookupRelaysTest {
    @Test
    fun profileLookupIncludesBootstrapAndActiveAccountRelaysWithoutDuplicates() {
        assertEquals(
            listOf(
                "wss://relay.us.whitenoise.chat",
                "wss://relay.eu.whitenoise.chat",
                "wss://relay.damus.io",
                "wss://nos.lol",
            ),
            profileLookupRelays(
                bootstrapRelays =
                    listOf(
                        "wss://relay.us.whitenoise.chat",
                        "wss://relay.eu.whitenoise.chat",
                    ),
                activeAccountRelays =
                    listOf(
                        "wss://relay.damus.io",
                        "wss://relay.us.whitenoise.chat",
                        "wss://nos.lol",
                    ),
            ),
        )
    }
}
