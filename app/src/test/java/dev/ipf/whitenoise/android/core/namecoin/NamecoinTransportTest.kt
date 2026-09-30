package dev.ipf.whitenoise.android.core.namecoin

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Transport tests for [NamecoinTransport]: the reply-id envelope check
 * (fixtures mirrored from `scripts/nc_shim_selftest.c`), the pinned-leaf-pubkey
 * trust manager, and a live WebSocket round trip against MockWebServer.
 */
class NamecoinTransportTest {
    private val server =
        NamecoinTransport.NamecoinServer(
            host = "nmc2.bitcoins.sk",
            port = 57002,
            pinBase64 = "dGVzdA==",
        )

    // ── replyMatchesId (carries the C shim's depth-1 contract) ─────────

    @Test
    fun replyMatchesIdFixtures() {
        // Nested {"result":{"id":99},"id":2} extracts 2, not 99.
        assertTrue(NamecoinTransport.replyMatchesId("""{"result":{"id":99},"id":2}""", 2))
        // Whitespace between key, colon and number is allowed.
        assertTrue(NamecoinTransport.replyMatchesId("""{"id" : 2,"result":null}""", 2))
        // No top-level id at all.
        assertFalse(NamecoinTransport.replyMatchesId("""{"result":null}""", 2))
        // `"id"` occurring inside a string value is ignored.
        assertTrue(NamecoinTransport.replyMatchesId("""{"data":"has \"id\": inside","id":7}""", 7))
        // Error envelope with an id still matches.
        assertTrue(
            NamecoinTransport.replyMatchesId(
                """{"jsonrpc":"2.0","error":{"code":-32601,"message":"nope"},"id":42}""",
                42,
            ),
        )
        // Server notification (ElectrumX subscription push): no top-level id.
        assertFalse(
            NamecoinTransport.replyMatchesId(
                """{"method":"blockchain.headers.subscribe","params":[{"hex":"...","height":1}]}""",
                1,
            ),
        )
        // Wrong id does not match; non-integer id does not match; garbage is
        // not a reply.
        assertFalse(NamecoinTransport.replyMatchesId("""{"id":3}""", 2))
        assertFalse(NamecoinTransport.replyMatchesId("""{"id":"2"}""", 2))
        assertFalse(NamecoinTransport.replyMatchesId("not json", 2))
        assertFalse(NamecoinTransport.replyMatchesId("""[1,2,3]""", 2))
    }

    // ── PinnedPublicKeyTrustManager ────────────────────────────────────

    // Two throwaway self-signed test certificates (openssl req -x509, keys
    // discarded). Only the public certs are embedded; the trust manager never
    // sees a private key.
    private val certA =
        """-----BEGIN CERTIFICATE-----
MIICsDCCAZgCCQCxjecAxdJo5TANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9u
YW1lY29pbi10ZXN0LWEwHhcNMjYwOTI5MjIzNTQ4WhcNMzYwOTI2MjIzNTQ4WjAa
MRgwFgYDVQQDDA9uYW1lY29pbi10ZXN0LWEwggEiMA0GCSqGSIb3DQEBAQUAA4IB
DwAwggEKAoIBAQDAmNYgb1PzTxZ53i7VLWY4Omp+ouqBrK7PBeFjX919ZYLVGDC3
dJs/rImnsSZmtN9PwNCyIFouuGxvkIHXXrcjjjFRQAINQimVvYAm0aeFmX60tVcN
r7ZYG4Veo87pvo/Y7SrDsop55R1zjZV02lolATrVpQSpZZpTBJMJG/s4o4ad+KVc
9VxnoCmhL49ZJ/kEysCNjOHVbY9nlroYRW2/0dads7nsdMveRCnzDNfzv+u2oHex
eWUyduoNchNMKNXpaLtvpYIGmUK3V5DVWLSovl17OGRrdJeM6ID4dY2zfDt7AQyK
0/+lka+uOB75GIqP1kxk+e9Rbaig0Od627MBAgMBAAEwDQYJKoZIhvcNAQELBQAD
ggEBAHniZj+i7XhtT2P8H0INDIxOUmHgOkcJyIPsyAiDQWrMB9QWS4gyVuAVenfb
/Zl8FVWvm+pSlP/RE8g0MyaDJ4CG1TKY0omrH0lg4dV+Q24PyWEa5JgCrAXZO4VB
iqrNr27EFaE6zxHFB3nXDqIDEaalLVZOQXE9lLysBqVitwUv0+eaWc7l22Dv/NQD
uIg9yPC+ErLJxnMcl/DtxYUaTXUdm0z36gpugAWi10H4ml2HnMmrImy5heaZPp9Q
dzurIuaCsDKJHefgjfBh22czzFYZlTnYHWMi33izM2f6zosmLn7kTDaxqFJAhNDU
cHYuywbGPA3HGwvpMKQUvHdGsyM=
-----END CERTIFICATE-----"""

    private val certB =
        """-----BEGIN CERTIFICATE-----
MIICsDCCAZgCCQDZxjD7Y5VPbzANBgkqhkiG9w0BAQsFADAaMRgwFgYDVQQDDA9u
YW1lY29pbi10ZXN0LWIwHhcNMjYwOTI5MjIzNTQ4WhcNMzYwOTI2MjIzNTQ4WjAa
MRgwFgYDVQQDDA9uYW1lY29pbi10ZXN0LWIwggEiMA0GCSqGSIb3DQEBAQUAA4IB
DwAwggEKAoIBAQDNZpcSWfZmfATxO2Z71YsrCbza4b5hU/HTN0LWIYZLr6nnKPDn
UhrJjSsI7dN3hfq0n07t+lEddZ04Kb7MqFf+eaMuzD9XbtmWl1k/97sIqYHNs+f5
Vb7ALEWfGwiIypyOj2urPxhxd8vELuKRUUgkYrTCgsj/flwFvnxs+GCRl1atmFwL
PIatLi/KRWfTWOAeemdy86xXzALGOVMC3yR53OX1Znj+2laEjRgFU4swfYl+bs0z
donYt9p0wI2WHyp1enf4jepyN1EAP4naAz2JHfiI5m+OAk0umI527JVPxxVeuciy
vjCSAGmP/VV/E+cFCQSW1uDORfVnkFsd9cwNAgMBAAEwDQYJKoZIhvcNAQELBQAD
ggEBAMEZ/jA9eyQQOW2PkOuqBAY70R7U+7v5fJ09g4rh9J22Lvj/B1eBVDPY9d0C
ZwfHHbpfEJBDnOxaiSqnAg79h7428QeK35BR1O0c07UfpaPb1qXd6x9znVZ1a0jB
tt3kkWyS0GlZcfFJel4TDsQiiwu5N+7gVWmPwZQzCgNT861QWLpX9aARqeNppZIK
5pP+n19HrzK+fgi/rMUFKO6Qq8pTJMHgwvR84a2CLFQu1obAA4cZg/mfkObchJqy
vLceh1+q26Slv3D8PxyRNHVHNhaHHHA0aUrLyYlHvS/rUffFEzRYNdXI0srKM0C8
bBvtKRl/cxazwQyikkaKTzByXNw=
-----END CERTIFICATE-----"""

    private fun loadCertificate(pem: String): X509Certificate =
        CertificateFactory
            .getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate

    private fun spkiPinBase64(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
        return Base64.getEncoder().encodeToString(digest)
    }

    @Test
    fun trustManagerAcceptsPinnedLeaf() {
        val certificate = loadCertificate(certA)
        val trustManager =
            NamecoinTransport.PinnedPublicKeyTrustManager(setOf(spkiPinBase64(certificate)))
        // Must not throw: the leaf SPKI matches the pin even though the cert
        // is self-signed and no CA vouches for it.
        trustManager.checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test
    fun trustManagerRejectsRotatedKey() {
        val presented = loadCertificate(certA)
        val pinnedOther = loadCertificate(certB)
        val trustManager =
            NamecoinTransport.PinnedPublicKeyTrustManager(setOf(spkiPinBase64(pinnedOther)))
        val failure =
            runCatching {
                trustManager.checkServerTrusted(arrayOf(presented), "RSA")
            }.exceptionOrNull()
        assertTrue(failure is CertificateException)
    }

    @Test
    fun trustManagerRejectsEmptyChain() {
        val trustManager = NamecoinTransport.PinnedPublicKeyTrustManager(setOf("dGVzdA=="))
        val failure =
            runCatching {
                trustManager.checkServerTrusted(emptyArray(), "RSA")
            }.exceptionOrNull()
        assertTrue(failure is CertificateException)
    }

    // ── live WebSocket round trip (plaintext mock) ─────────────────────

    private val mockWebServer = MockWebServer()

    // WebSockets need a zero read timeout; the client is shared across the
    // round-trip tests and torn down with the server.
    private val httpClient =
        OkHttpClient
            .Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

    @After
    fun tearDown() {
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdownNow()
        mockWebServer.shutdown()
    }

    @Test
    fun callReturnsReplyWithMatchingId() =
        runBlocking {
            mockWebServer.useWebSocketUpgradeReply(
                """{"jsonrpc":"2.0","id":2,"result":{"height":1}}""",
            )
            mockWebServer.start()
            val transport = NamecoinTransport { httpClient }
            val body =
                transport.call(
                    server = server,
                    url = mockWebServer.url("/").toString().replace("http://", "ws://"),
                    requestJson = """{"jsonrpc":"2.0","id":2,"method":"blockchain.headers.subscribe","params":[]}""",
                    wantId = 2,
                    timeoutMillis = 5_000,
                )
            assertEquals("""{"jsonrpc":"2.0","id":2,"result":{"height":1}}""", body)
        }

    @Test
    fun callSkipsNotificationsUntilReply() =
        runBlocking {
            mockWebServer.useWebSocketUpgradeReply(
                """{"method":"blockchain.headers.subscribe","params":[{"height":9}]}""",
                """{"id":99,"result":null}""",
                """{"jsonrpc":"2.0","id":2,"result":{"height":1}}""",
            )
            mockWebServer.start()
            val transport = NamecoinTransport { httpClient }
            val body =
                transport.call(
                    server = server,
                    url = mockWebServer.url("/").toString().replace("http://", "ws://"),
                    requestJson = """{"jsonrpc":"2.0","id":2,"method":"m","params":[]}""",
                    wantId = 2,
                    timeoutMillis = 5_000,
                )
            assertEquals("""{"jsonrpc":"2.0","id":2,"result":{"height":1}}""", body)
        }

    @Test
    fun callTimesOutWithoutMatchingReply() =
        runBlocking {
            mockWebServer.useWebSocketUpgradeReply("""{"id":99,"result":null}""")
            mockWebServer.start()
            val transport = NamecoinTransport { httpClient }
            val started = System.nanoTime()
            val body =
                transport.call(
                    server = server,
                    url = mockWebServer.url("/").toString().replace("http://", "ws://"),
                    requestJson = """{"jsonrpc":"2.0","id":2,"method":"m","params":[]}""",
                    wantId = 2,
                    timeoutMillis = 1_000,
                )
            assertNull(body)
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("timeout should bound the call (took ${elapsedMillis}ms)", elapsedMillis < 4_000)
        }

    @Test
    fun callDropsOversizeMessage() =
        runBlocking {
            val oversized = """{"id":2,"result":"""" + "x".repeat(NamecoinTransport.MAX_REPLY_CHARS) + """"}"""
            mockWebServer.useWebSocketUpgradeReply(oversized)
            mockWebServer.start()
            val transport = NamecoinTransport { httpClient }
            val body =
                transport.call(
                    server = server,
                    url = mockWebServer.url("/").toString().replace("http://", "ws://"),
                    requestJson = """{"jsonrpc":"2.0","id":2,"method":"m","params":[]}""",
                    wantId = 2,
                    timeoutMillis = 1_000,
                )
            // The oversize message is not parsed; the call dies by timeout.
            assertNull(body)
        }

    /** Enqueue a WebSocket upgrade that replies to any text frame with the given messages. */
    private fun MockWebServer.useWebSocketUpgradeReply(vararg replies: String) {
        val listener =
            object : WebSocketListener() {
                override fun onMessage(
                    webSocket: okhttp3.WebSocket,
                    text: String,
                ) {
                    for (reply in replies) webSocket.send(reply)
                }

                override fun onMessage(
                    webSocket: okhttp3.WebSocket,
                    bytes: ByteString,
                ) = Unit

                override fun onClosing(
                    webSocket: okhttp3.WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    // Answer the close handshake so MockWebServer.shutdown
                    // does not hang on an open socket.
                    webSocket.close(code, reason)
                }
            }
        enqueue(MockResponse().withWebSocketUpgrade(listener))
    }
}
