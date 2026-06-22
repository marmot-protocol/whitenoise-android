package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure string codec backing `UriListSaver`, which persists the
 * staged-attachment shelf across process death (issue #531). The Android `Uri`
 * conversion is split out of the codec so the separator and empty-list contract
 * can be exercised on the JVM without Robolectric.
 */
class UriListSaverCodecTest {
    @Test
    fun emptyListRoundTripsToTheNoPreviewSentinel() {
        assertEquals("", encodeUriListTokens(emptyList()))
        assertEquals(emptyList<String>(), decodeUriListTokens(""))
    }

    @Test
    fun singleTokenRoundTrips() {
        val tokens = listOf("content://media/external/images/media/42")
        assertEquals(tokens, decodeUriListTokens(encodeUriListTokens(tokens)))
    }

    @Test
    fun multipleTokensRoundTripInOrder() {
        val tokens =
            listOf(
                "content://media/external/images/media/1",
                "content://dev.ipf.darkmatter.fileprovider/camera/capture_2.jpg",
                "content://media/external/images/media/3",
            )
        val encoded = encodeUriListTokens(tokens)
        assertEquals(tokens.joinToString("\n"), encoded)
        assertEquals(tokens, decodeUriListTokens(encoded))
    }

    @Test
    fun newlineSeparatorJoinsTokens() {
        assertEquals("a\nb\nc", encodeUriListTokens(listOf("a", "b", "c")))
    }

    @Test
    fun decodeDropsBlankTokensFromDoubledOrTrailingSeparators() {
        assertEquals(listOf("a", "b"), decodeUriListTokens("a\n\nb\n"))
        assertEquals(listOf("a"), decodeUriListTokens("\na"))
    }
}
