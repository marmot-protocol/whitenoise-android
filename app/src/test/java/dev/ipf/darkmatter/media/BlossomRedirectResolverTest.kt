package dev.ipf.darkmatter.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class BlossomRedirectResolverTest {
    /**
     * Recording fake — HttpURLConnection is abstract, so we capture the request
     * configuration the resolver applies without performing any real network IO.
     */
    private class RecordingConnection(
        url: URL,
    ) : HttpURLConnection(url) {
        val capturedHeaders = mutableMapOf<String, String>()

        override fun setRequestProperty(
            key: String,
            value: String,
        ) {
            capturedHeaders[key] = value
        }

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false
    }

    @Test
    fun configureProbeConnectionSendsSingleByteRangedGet() {
        val conn = RecordingConnection(URL("https://blossom.primal.net/abc"))

        BlossomRedirectResolver.configureProbeConnection(conn)

        // GET is required because primal answers HEAD with 200 from the
        // canonical host and only emits the 30x on GET.
        assertEquals("GET", conn.requestMethod)
        // Must not auto-follow: the resolver walks each hop itself so it can
        // run an SSRF check before chasing a Location header.
        assertFalse(conn.instanceFollowRedirects)
        assertFalse(conn.useCaches)
        // The whole point of #226: ask for a single byte so the terminal 2xx
        // host does not stream the entire (multi-MB) blob we then discard.
        assertEquals("bytes=0-0", conn.capturedHeaders["Range"])
    }
}
