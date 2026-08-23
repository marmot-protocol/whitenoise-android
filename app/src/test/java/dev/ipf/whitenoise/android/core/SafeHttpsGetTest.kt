package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
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

    @Test
    fun timeoutIsClampedToTheRemainingAbsoluteDeadline() {
        val now = 1_000_000_000L

        assertEquals(
            250,
            SafeHttpsGet.timeoutMillisWithinDeadline(
                configuredTimeoutMillis = 5_000,
                deadlineNanos = now + 250_000_000L,
                nowNanos = now,
            ),
        )
        assertEquals(
            1,
            SafeHttpsGet.timeoutMillisWithinDeadline(
                configuredTimeoutMillis = 5_000,
                deadlineNanos = now + 1L,
                nowNanos = now,
            ),
        )
        assertEquals(
            null,
            SafeHttpsGet.timeoutMillisWithinDeadline(
                configuredTimeoutMillis = 5_000,
                deadlineNanos = now,
                nowNanos = now,
            ),
        )
    }

    @Test
    fun responseHeaderReadIsBoundedAndDeadlineCheckedAfterItReturns() {
        // Retained intentionally: a fake response returns immediately, so this
        // uniquely pins the timeout clamp and post-header deadline re-check.
        val source = safeHttpsGetSource().readText()
        val responseRead = source.indexOf("val code = connection.responseCode")
        val timeoutClamp =
            source.lastIndexOf("timeoutMillisWithinDeadline(readTimeoutMillis, requestDeadlineNanos)", responseRead)
        val deadlineRecheck = source.indexOf("if (deadlineExceeded(requestDeadlineNanos)) return null", responseRead)

        assertTrue(timeoutClamp >= 0)
        assertTrue(responseRead > timeoutClamp)
        assertTrue(deadlineRecheck > responseRead)
    }

    @Test
    fun pinnedAddressLoopObservesRequestDeadlineBetweenConnectAttempts() {
        // Retained intentionally: the injected transport is already connected,
        // so this uniquely pins the deadline check between real IP attempts.
        val source = safeHttpsGetSource().readText()
        val openPinnedConnection = source.kotlinFunctionBody("openPinnedConnection")

        assertTrue(
            "request deadline must be passed into the pinned-address loop",
            "requestDeadlineNanos = requestDeadlineNanos" in source &&
                "requestDeadlineNanos: Long" in source,
        )
        assertTrue(
            "pinned-address loop must stop once the request deadline is spent",
            Regex("""for\s*\(\s*address\s+in\s+addresses\s*\)\s*\{\s*if\s*\(\s*deadlineExceeded\(requestDeadlineNanos\)\s*\)\s*return\s+null""")
                .containsMatchIn(openPinnedConnection),
        )
    }

    @Test
    fun getRejectsInvalidAuthoritiesBeforeResolvingOrOpening() {
        val invalidUrls =
            listOf(
                "http://example.test/path",
                "https://user:secret@example.test/path",
                "https://example.test:444/path",
                "https://127.0.0.1/path",
            )

        invalidUrls.forEach { url ->
            var resolverCalls = 0
            var openerCalls = 0
            assertNull(
                SafeHttpsGet.get(
                    url = url,
                    maxBodyBytes = 32,
                    connectTimeoutMillis = 1_000,
                    readTimeoutMillis = 1_000,
                    dependencies =
                        dependencies(
                            resolve = {
                                resolverCalls += 1
                                PUBLIC_ADDRESSES
                            },
                            open = {
                                openerCalls += 1
                                fakeConnection(it.parsed)
                            },
                        ),
                ),
            )
            assertEquals("resolver must not run for $url", 0, resolverCalls)
            assertEquals("transport must not open for $url", 0, openerCalls)
        }

        var resolved = false
        assertNull(
            SafeHttpsGet.get(
                url = "https://example.test/path",
                maxBodyBytes = 32,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                hostAllowed = { false },
                dependencies =
                    dependencies(
                        resolve = {
                            resolved = true
                            PUBLIC_ADDRESSES
                        },
                    ),
            ),
        )
        assertFalse(resolved)
    }

    @Test
    fun getRejectsMixedPublicAndPrivateDnsAnswersWithoutConnecting() {
        var opened = false

        val result =
            SafeHttpsGet.get(
                url = "https://example.test/path",
                maxBodyBytes = 32,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                dependencies =
                    dependencies(
                        resolve = {
                            arrayOf(
                                InetAddress.getByName("8.8.8.8"),
                                InetAddress.getByName("10.0.0.7"),
                            )
                        },
                        open = {
                            opened = true
                            fakeConnection(it.parsed)
                        },
                    ),
            )

        assertNull(result)
        assertFalse(opened)
    }

    @Test
    fun getExecutesSuccessAndRejectsNonSuccessResponses() {
        val success = fakeConnection(URL("https://example.test/path"), body = "safe".toByteArray())
        assertEquals(
            "safe",
            SafeHttpsGet
                .get(
                    url = "https://example.test/path",
                    maxBodyBytes = 32,
                    connectTimeoutMillis = 1_000,
                    readTimeoutMillis = 1_000,
                    dependencies = dependencies(open = { success }),
                )?.toString(Charsets.UTF_8),
        )
        assertTrue(success.disconnected)

        val failure = fakeConnection(URL("https://example.test/path"), responseCode = 503)
        assertNull(
            SafeHttpsGet.get(
                url = "https://example.test/path",
                maxBodyBytes = 32,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                dependencies = dependencies(open = { failure }),
            ),
        )
        assertTrue(failure.disconnected)
    }

    @Test
    fun getRejectsDeclaredAndActualBodiesOverTheCap() {
        val overDeclared =
            fakeConnection(
                URL("https://example.test/declared"),
                body = "tiny".toByteArray(),
                declaredLength = 10_000,
            )
        assertNull(
            SafeHttpsGet.get(
                url = "https://example.test/declared",
                maxBodyBytes = 4,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                dependencies = dependencies(open = { overDeclared }),
            ),
        )
        assertEquals(0, overDeclared.inputStreamReads)

        val underDeclared =
            fakeConnection(
                URL("https://example.test/actual"),
                body = "larger-than-cap".toByteArray(),
                declaredLength = 1,
            )
        assertNull(
            SafeHttpsGet.get(
                url = "https://example.test/actual",
                maxBodyBytes = 4,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                dependencies = dependencies(open = { underDeclared }),
            ),
        )
        assertTrue(underDeclared.inputStreamReads > 0)
    }

    @Test
    fun getRevalidatesRedirectsAndStripsCrossOriginCredentials() {
        val opened = mutableListOf<SafeHttpsPinnedRequest>()
        val first =
            fakeConnection(
                URL("https://example.test/start"),
                responseCode = 302,
                location = "https://other.test/next",
            )
        val second = fakeConnection(URL("https://other.test/next"), body = "done".toByteArray())

        val result =
            SafeHttpsGet.get(
                url = "https://example.test/start",
                maxBodyBytes = 32,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                requestHeaders =
                    mapOf(
                        "Authorization" to "Bearer secret",
                        "Cookie" to "sid=secret",
                        "Accept" to "application/json",
                    ),
                dependencies =
                    dependencies(
                        open = { request ->
                            opened += request
                            if (opened.size == 1) first else second
                        },
                    ),
            )

        assertEquals("done", result?.toString(Charsets.UTF_8))
        assertEquals("Bearer secret", opened.first().requestHeaders["Authorization"])
        assertFalse(opened.last().requestHeaders.containsKey("Authorization"))
        assertFalse(opened.last().requestHeaders.containsKey("Cookie"))
        assertEquals("application/json", opened.last().requestHeaders["Accept"])
        assertTrue(first.disconnected)
        assertTrue(second.disconnected)
    }

    @Test
    fun getRejectsRedirectDowngradePrivateDestinationAndHopOverflow() {
        listOf(
            "http://example.test/insecure",
            "https://127.0.0.1/private",
        ).forEach { location ->
            var opens = 0
            assertNull(
                SafeHttpsGet.get(
                    url = "https://example.test/start",
                    maxBodyBytes = 32,
                    connectTimeoutMillis = 1_000,
                    readTimeoutMillis = 1_000,
                    dependencies =
                        dependencies(
                            open = {
                                opens += 1
                                fakeConnection(it.parsed, responseCode = 302, location = location)
                            },
                        ),
                ),
            )
            assertEquals(1, opens)
        }

        var hopOpens = 0
        assertNull(
            SafeHttpsGet.get(
                url = "https://example.test/start",
                maxBodyBytes = 32,
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                maxRedirectHops = 2,
                dependencies =
                    dependencies(
                        open = {
                            hopOpens += 1
                            fakeConnection(it.parsed, responseCode = 302, location = "/again")
                        },
                    ),
            ),
        )
        assertEquals(3, hopOpens)
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
    fun pinnedSocketDelegatesRepresentativeTlsSurfaceIncludingSession() {
        val session = fakeSslSession()
        val fake = FakeSslSocket(session)
        val wrapper = SniPinnedSslSocket(fake, "cdn.example.com")

        wrapper.enabledCipherSuites = arrayOf("TLS_TEST_CIPHER")
        wrapper.enabledProtocols = arrayOf("TLSv1.3")
        wrapper.useClientMode = false
        wrapper.needClientAuth = true
        wrapper.enableSessionCreation = false

        val delegatedValues =
            listOf(
                "session" to (wrapper.session === session),
                "enabledCipherSuites" to (wrapper.enabledCipherSuites.toList() == listOf("TLS_TEST_CIPHER")),
                "enabledProtocols" to (wrapper.enabledProtocols.toList() == listOf("TLSv1.3")),
                "useClientMode" to !wrapper.useClientMode,
                "needClientAuth" to wrapper.needClientAuth,
                "enableSessionCreation" to !wrapper.enableSessionCreation,
            )

        delegatedValues.forEach { (property, delegated) ->
            assertTrue("$property must delegate to the connected TLS socket", delegated)
        }
        assertSame(session, fake.session)
    }

    private fun dependencies(
        resolve: (String) -> Array<InetAddress>? = { PUBLIC_ADDRESSES },
        open: (SafeHttpsPinnedRequest) -> HttpURLConnection? = { fakeConnection(it.parsed) },
    ): SafeHttpsGetDependencies = SafeHttpsGetDependencies(resolve, open)

    private fun fakeConnection(
        url: URL,
        responseCode: Int = 200,
        location: String? = null,
        body: ByteArray = byteArrayOf(),
        declaredLength: Long? = null,
    ): FakeHttpConnection =
        FakeHttpConnection(
            url = url,
            responseCodeValue = responseCode,
            location = location,
            body = body,
            declaredLength = declaredLength ?: body.size.toLong(),
        )

    private fun fakeSslSession(): SSLSession =
        Proxy.newProxyInstance(
            SSLSession::class.java.classLoader,
            arrayOf(SSLSession::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "fake-ssl-session"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SSLSession

    private fun safeHttpsGetSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/core/SafeHttpsGet.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/core/SafeHttpsGet.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing SafeHttpsGet.kt source file")

    private fun String.kotlinFunctionBody(functionName: String): String {
        val start =
            Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
                .find(this)
                ?.range
                ?.first
                ?: error("Missing function $functionName")
        val braceStart = indexOf('{', start)
        require(braceStart >= 0) { "Missing body for $functionName" }
        var depth = 0
        for (index in braceStart until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(braceStart + 1, index)
                }
            }
        }
        error("Unterminated body for $functionName")
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

    private class FakeHttpConnection(
        url: URL,
        private val responseCodeValue: Int,
        private val location: String?,
        private val body: ByteArray,
        private val declaredLength: Long,
    ) : HttpURLConnection(url) {
        var disconnected = false
        var inputStreamReads = 0

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = responseCodeValue

        override fun getHeaderField(name: String?): String? = location.takeIf { name.equals("Location", true) }

        override fun getContentLengthLong(): Long = declaredLength

        override fun getInputStream(): InputStream =
            object : ByteArrayInputStream(body) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    inputStreamReads += 1
                    return super.read(buffer, offset, length)
                }
            }
    }

    private class FakeSslSocket(
        private val sessionValue: SSLSession? = null,
    ) : SSLSocket() {
        var appliedParameters: SSLParameters? = null
        private var cipherSuites = emptyArray<String>()
        private var protocols = emptyArray<String>()
        private var clientMode = true
        private var clientAuthNeeded = false
        private var sessionCreation = true

        override fun setSSLParameters(params: SSLParameters) {
            appliedParameters = params
        }

        override fun getSSLParameters(): SSLParameters = appliedParameters ?: SSLParameters()

        override fun getSupportedCipherSuites(): Array<String> = emptyArray()

        override fun getEnabledCipherSuites(): Array<String> = cipherSuites

        override fun setEnabledCipherSuites(suites: Array<String>?) {
            cipherSuites = suites?.map(String::toString)?.toTypedArray() ?: emptyArray()
        }

        override fun getSupportedProtocols(): Array<String> = emptyArray()

        override fun getEnabledProtocols(): Array<String> = protocols

        override fun setEnabledProtocols(protocols: Array<String>?) {
            this.protocols = protocols?.map(String::toString)?.toTypedArray() ?: emptyArray()
        }

        override fun getSession(): SSLSession = sessionValue ?: throw UnsupportedOperationException()

        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit

        override fun startHandshake() = Unit

        override fun setUseClientMode(mode: Boolean) {
            clientMode = mode
        }

        override fun getUseClientMode(): Boolean = clientMode

        override fun setNeedClientAuth(need: Boolean) {
            clientAuthNeeded = need
        }

        override fun getNeedClientAuth(): Boolean = clientAuthNeeded

        override fun setWantClientAuth(want: Boolean) = Unit

        override fun getWantClientAuth(): Boolean = false

        override fun setEnableSessionCreation(flag: Boolean) {
            sessionCreation = flag
        }

        override fun getEnableSessionCreation(): Boolean = sessionCreation
    }

    private companion object {
        val PUBLIC_ADDRESSES = arrayOf(InetAddress.getByName("8.8.8.8"))
    }
}
