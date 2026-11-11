package dev.ipf.whitenoise.android.ui.conversation.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

/** An event's `imeta` fields keep author order per name, and a tag with many repeats is read in linear time. */
class NostrEventImetaPropertiesTest {
    @Test
    fun repeatedNamesKeepAuthorOrder() {
        val properties =
            imetaProperties(
                listOf(
                    "imeta",
                    "url https://a.example/1.mp4",
                    "M video/mp4",
                    "url https://a.example/2.mp4",
                    "dim",
                    " x",
                ),
            )

        assertEquals(listOf("https://a.example/1.mp4", "https://a.example/2.mp4"), properties["url"])
        assertEquals(listOf("video/mp4"), properties["m"])
        assertEquals(setOf("url", "m"), properties.keys)
    }

    /** Appending each repeat in place: copying the prior list per field was quadratic in one accepted-size tag. */
    @Test(timeout = 5_000)
    fun manyRepeatsOfOneNameStayLinear() {
        val repeats = 200_000
        val properties = imetaProperties(listOf("imeta") + List(repeats) { "x $it" })

        assertEquals(repeats, properties.getValue("x").size)
        assertEquals("0", properties.getValue("x").first())
        assertEquals("${repeats - 1}", properties.getValue("x").last())
    }
}
