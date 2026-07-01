package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URL

class SafeHttpsGetTest {
    @Test
    fun keepsHeadersOnSameOriginRedirect() {
        val headers =
            mapOf(
                "Authorization" to "Bearer secret",
                "Cookie" to "sid=secret",
                "Accept" to "application/json",
            )

        assertEquals(
            headers,
            SafeHttpsGet.headersForHop(
                requestHeaders = headers,
                original = URL("https://example.com/start"),
                current = URL("https://example.com/next"),
            ),
        )
    }

    @Test
    fun stripsSensitiveHeadersOnCrossOriginRedirect() {
        val filtered =
            SafeHttpsGet.headersForHop(
                requestHeaders =
                    mapOf(
                        "Authorization" to "Bearer secret",
                        "authorization" to "Bearer lower",
                        "Cookie" to "sid=secret",
                        "Proxy-Authorization" to "Basic secret",
                        "Accept" to "application/json",
                        "User-Agent" to "WhiteNoise",
                    ),
                original = URL("https://example.com/start"),
                current = URL("https://images.example.net/next"),
            )

        assertEquals("application/json", filtered["Accept"])
        assertEquals("WhiteNoise", filtered["User-Agent"])
        assertFalse(filtered.containsKey("Authorization"))
        assertFalse(filtered.containsKey("authorization"))
        assertFalse(filtered.containsKey("Cookie"))
        assertFalse(filtered.containsKey("Proxy-Authorization"))
    }
}
