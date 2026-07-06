package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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

    @Test
    fun requestDeadlineUsesAnAbsoluteBudgetBeyondOneReadTimeout() {
        assertEquals(
            20_000L,
            SafeHttpsGet.requestDeadlineMillis(connectTimeoutMillis = 5_000, readTimeoutMillis = 10_000),
        )
        assertEquals(
            15_000L,
            SafeHttpsGet.requestDeadlineMillis(connectTimeoutMillis = 12_000, readTimeoutMillis = 3_000),
        )
    }

    @Test
    fun deadlineExceededTreatsPastDeadlineAsExpired() {
        assertTrue(SafeHttpsGet.deadlineExceeded(System.nanoTime() - 1_000_000L))
        assertFalse(SafeHttpsGet.deadlineExceeded(System.nanoTime() + 60_000_000_000L))
    }

    // ---- DNS-rebinding pin (#982): the connection dials the vetted IP while
    // ---- SNI / Host / certificate verification keep the original hostname.

    @Test
    fun pinnedUrlSwapsAuthorityForTheIpLiteralKeepingPathAndQuery() {
        // getByName on a literal is a parse, never a DNS query.
        val address = InetAddress.getByName("203.0.113.7")

        assertEquals(
            "https://203.0.113.7/media/avatar.png?size=64",
            SafeHttpsGet.pinnedUrl(URL("https://cdn.example.com/media/avatar.png?size=64"), address).toString(),
        )
    }

    @Test
    fun pinnedUrlBracketsIpv6Literals() {
        val address = InetAddress.getByName("2001:4860:4860::8888")

        assertEquals(
            "https://[2001:4860:4860:0:0:0:0:8888]/img",
            SafeHttpsGet.pinnedUrl(URL("https://cdn.example.com/img"), address).toString(),
        )
    }

    @Test
    fun pinnedVerifierChecksTheCertificateAgainstTheHostnameNotTheIp() {
        // The stack passes the connection URL's host — the pinned IP literal —
        // to the verifier. The pinned verifier must ignore it and run the
        // platform's default verifier against the ORIGINAL hostname, so the
        // certificate identity requirement is exactly what a non-pinned
        // request would enforce.
        val session =
            Proxy.newProxyInstance(
                SSLSession::class.java.classLoader,
                arrayOf(SSLSession::class.java),
            ) { proxy, method, args ->
                // Identity-only Object protocol; the verifier must never
                // inspect the session itself.
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "fake-ssl-session"
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as SSLSession
        val previous = HttpsURLConnection.getDefaultHostnameVerifier()
        val seen = mutableListOf<Pair<String, SSLSession>>()
        HttpsURLConnection.setDefaultHostnameVerifier { hostname, verifiedSession ->
            seen += hostname to verifiedSession
            true
        }
        try {
            assertTrue(SafeHttpsGet.pinnedHostnameVerifier("cdn.example.com").verify("203.0.113.7", session))
        } finally {
            HttpsURLConnection.setDefaultHostnameVerifier(previous)
        }

        assertEquals(listOf("cdn.example.com" to session), seen)
    }

    @Test
    fun sniFactoryLayersTlsWithTheOriginalHostname() {
        val layeredHosts = mutableListOf<String?>()
        val fake = FakeSslSocket()
        val delegate =
            object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = emptyArray()

                override fun getSupportedCipherSuites(): Array<String> = emptyArray()

                override fun createSocket(
                    socket: Socket?,
                    host: String?,
                    port: Int,
                    autoClose: Boolean,
                ): Socket {
                    layeredHosts += host
                    return fake
                }

                override fun createSocket(
                    host: String?,
                    port: Int,
                ): Socket = throw UnsupportedOperationException()

                override fun createSocket(
                    host: String?,
                    port: Int,
                    localHost: InetAddress?,
                    localPort: Int,
                ): Socket = throw UnsupportedOperationException()

                override fun createSocket(
                    host: InetAddress?,
                    port: Int,
                ): Socket = throw UnsupportedOperationException()

                override fun createSocket(
                    address: InetAddress?,
                    port: Int,
                    localAddress: InetAddress?,
                    localPort: Int,
                ): Socket = throw UnsupportedOperationException()
            }

        val socket =
            SniPinningSslSocketFactory(delegate, "cdn.example.com")
                .createSocket(Socket(), "203.0.113.7", 443, true)

        // TLS is layered under the real hostname (SNI + JSSE peer identity),
        // not the pinned IP literal the stack passed in…
        assertEquals(listOf<String?>("cdn.example.com"), layeredHosts)
        // …and wrapped so later stack-applied parameters can't re-label it.
        assertTrue(socket is SniPinnedSslSocket)
    }

    @Test
    fun pinnedSocketRepinsSniWhenTheStackReappliesParameters() {
        // Android's HttpsURLConnection re-applies SSLParameters carrying the
        // URL host (the IP literal) as the SNI server name after the socket
        // factory runs. The wrapper must restore the hostname while keeping
        // the rest of the parameters object intact.
        val fake = FakeSslSocket()
        val wrapper = SniPinnedSslSocket(fake, "cdn.example.com")
        val reapplied = SSLParameters()
        reapplied.serverNames = listOf(SNIHostName("203.0.113.7"))

        wrapper.sslParameters = reapplied

        assertEquals(
            listOf(SNIHostName("cdn.example.com")),
            fake.appliedParameters?.serverNames,
        )
    }

    private class FakeSslSocket : SSLSocket() {
        var appliedParameters: SSLParameters? = null

        override fun setSSLParameters(params: SSLParameters) {
            appliedParameters = params
        }

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun getEnabledCipherSuites(): Array<String> = emptyArray()

        override fun setEnabledCipherSuites(suites: Array<String>?) = Unit

        override fun getSupportedProtocols(): Array<String> = emptyArray()

        override fun getEnabledProtocols(): Array<String> = emptyArray()

        override fun setEnabledProtocols(protocols: Array<String>?) = Unit

        override fun getSession(): SSLSession = throw UnsupportedOperationException()

        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

        override fun startHandshake() = Unit

        override fun setUseClientMode(mode: Boolean) = Unit

        override fun getUseClientMode(): Boolean = true

        override fun setNeedClientAuth(need: Boolean) = Unit

        override fun getNeedClientAuth(): Boolean = false

        override fun setWantClientAuth(want: Boolean) = Unit

        override fun getWantClientAuth(): Boolean = false

        override fun setEnableSessionCreation(flag: Boolean) = Unit

        override fun getEnableSessionCreation(): Boolean = true
    }
}
