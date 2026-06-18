package dev.ipf.darkmatter.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSearchClientTest {
    @Test
    fun sanitizeAllowsOnlyPublicHttpsHosts() {
        assertNull(sanitizeHttpsAvatarUrl(null))
        assertNull(sanitizeHttpsAvatarUrl("  "))
        assertNull(sanitizeHttpsAvatarUrl("http://example.com/a.png"))
        assertNull(sanitizeHttpsAvatarUrl("https:///missing-host.png"))
        assertNull(sanitizeHttpsAvatarUrl("https://127.0.0.1/a.png"))
        assertNull(sanitizeHttpsAvatarUrl("https://192.168.1.1/a.png"))
        assertEquals("https://example.com/a.png", sanitizeHttpsAvatarUrl("https://example.com/a.png"))
        // Scheme-relative inputs are upgraded to https before validation.
        assertEquals("https://example.com/a.png", sanitizeHttpsAvatarUrl("//example.com/a.png"))
    }

    @Test
    fun sanitizeRejectsEmbeddedCredentials() {
        assertNull(sanitizeHttpsAvatarUrl("https://user:pass@example.com/a.png"))
        assertNull(sanitizeHttpsAvatarUrl("https://user@example.com/a.png"))
    }

    @Test
    fun duckDuckGoFetchUrlIsPinnedToDuckDuckGoHosts() {
        assertEquals(
            "https://duckduckgo.com/?q=marmot&iax=images&ia=images",
            sanitizeDuckDuckGoFetchUrl("https://duckduckgo.com/?q=marmot&iax=images&ia=images"),
        )
        assertEquals(
            "https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Fexample.com%2Fa.png",
            sanitizeDuckDuckGoFetchUrl("https://external-content.duckduckgo.com/iu/?u=https%3A%2F%2Fexample.com%2Fa.png"),
        )
        assertEquals(
            "https://duckduckgo.com/i.js?q=marmot",
            sanitizeDuckDuckGoFetchUrl("//duckduckgo.com/i.js?q=marmot"),
        )

        assertNull(sanitizeDuckDuckGoFetchUrl("https://example.com/i.js?q=marmot"))
        assertNull(sanitizeDuckDuckGoFetchUrl("https://duckduckgo.com.evil.example/i.js?q=marmot"))
        assertNull(sanitizeDuckDuckGoFetchUrl("https://duckduckgo.com@evil.example/i.js?q=marmot"))
        assertNull(sanitizeDuckDuckGoFetchUrl("http://duckduckgo.com/i.js?q=marmot"))
        assertNull(sanitizeDuckDuckGoFetchUrl("https://127.0.0.1/i.js?q=marmot"))
    }
}
