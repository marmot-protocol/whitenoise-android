package dev.ipf.whitenoise.android.core.namecoin

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Transport tests for [NamecoinTransport]: the reply-id envelope check
 * (fixtures mirrored from `scripts/nc_shim_selftest.c`), the pinned-leaf-pubkey
 * trust manager, and real blocking socket round trips/stall cancellation.
 */
class NamecoinTransportTest {
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

    @Test
    fun pinsAreIsolatedPerOperatorAndSharedWithIpAlias() {
        val certA = loadCertificate(certA)
        val certB = loadCertificate(certB)
        val nmc =
            NamecoinTransport.NamecoinServer(
                host = "nmc2.bitcoins.sk",
                port = 57002,
                pinBase64 = spkiPinBase64(certA),
            )
        val nmcIp = nmc.copy(host = "46.229.238.187", tlsHostname = nmc.host)
        val relay = nmc.copy(host = "relay.testls.bit", pinBase64 = spkiPinBase64(certB))

        for (endpoint in listOf(nmc, nmcIp)) {
            NamecoinTransport.trustManagerFor(endpoint)!!.checkServerTrusted(arrayOf(certA), "RSA")
            assertTrue(
                runCatching {
                    NamecoinTransport.trustManagerFor(endpoint)!!.checkServerTrusted(arrayOf(certB), "RSA")
                }.exceptionOrNull() is CertificateException,
            )
        }
        NamecoinTransport.trustManagerFor(relay)!!.checkServerTrusted(arrayOf(certB), "RSA")
        assertTrue(
            runCatching {
                NamecoinTransport.trustManagerFor(relay)!!.checkServerTrusted(arrayOf(certA), "RSA")
            }.exceptionOrNull() is CertificateException,
        )
        assertEquals("nmc2.bitcoins.sk", nmcIp.tlsHostname)
        assertNull(NamecoinTransport.trustManagerFor(nmc.copy(pinBase64 = null)))
    }

    // ── real blocking socket round trips ───────────────────────────────

    private fun plaintextTransport() =
        NamecoinTransport { server, socket ->
            socket.soTimeout = NamecoinTransport.READ_TIMEOUT_MILLIS
            socket.connect(
                InetSocketAddress(server.host, server.port),
                NamecoinTransport.CONNECT_TIMEOUT_MILLIS,
            )
            socket
        }

    private fun withSocketServer(
        handler: (Socket) -> Unit,
        test: suspend (NamecoinTransport.NamecoinServer) -> Unit,
    ) = runBlocking {
        ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }.use { listener ->
            val worker =
                thread(isDaemon = true) {
                    runCatching { listener.accept().use(handler) }
                }
            try {
                test(NamecoinTransport.NamecoinServer("127.0.0.1", listener.localPort))
            } finally {
                runCatching { listener.close() }
                worker.join(1_000)
            }
        }
    }

    @Test
    fun callReturnsReplyWithMatchingId() =
        withSocketServer(
            handler = { socket ->
                val request = socket.getInputStream().bufferedReader().readLine()
                assertEquals("""{"jsonrpc":"2.0","id":2,"method":"m","params":[]}""", request)
                socket.getOutputStream().bufferedWriter().use {
                    it.write("""{"jsonrpc":"2.0","id":2,"result":{"height":1}}""")
                    it.newLine()
                    it.flush()
                }
            },
            test = { server ->
                val body =
                    plaintextTransport().call(
                        server = server,
                        requestJson = """{"jsonrpc":"2.0","id":2,"method":"m","params":[]}""",
                        wantId = 2,
                        timeoutMillis = 5_000,
                    )
                assertEquals("""{"jsonrpc":"2.0","id":2,"result":{"height":1}}""", body)
            },
        )

    @Test
    fun callSkipsNotificationsUntilReply() =
        withSocketServer(
            handler = { socket ->
                socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().bufferedWriter().use {
                    it.write("""{"method":"blockchain.headers.subscribe","params":[{"height":9}]}""" + "\n")
                    it.write("""{"id":99,"result":null}""" + "\n")
                    it.write("""{"jsonrpc":"2.0","id":2,"result":{"height":1}}""" + "\n")
                    it.flush()
                }
            },
            test = { server ->
                val body = plaintextTransport().call(server, """{"id":2}""", 2, 5_000)
                assertEquals("""{"jsonrpc":"2.0","id":2,"result":{"height":1}}""", body)
            },
        )

    @Test
    fun callTimesOutAndClosesStalledRead() =
        withSocketServer(
            handler = { socket ->
                socket.getInputStream().bufferedReader().readLine()
                Thread.sleep(2_000)
            },
            test = { server ->
                val started = System.nanoTime()
                val body = plaintextTransport().call(server, """{"id":2}""", 2, 250)
                assertNull(body)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue("stalled read returned after ${elapsed}ms", elapsed < 1_500)
            },
        )

    @Test
    fun callerCancellationClosesUnderlyingSocket() =
        withSocketServer(
            handler = { socket ->
                socket.getInputStream().bufferedReader().readLine()
                Thread.sleep(5_000)
            },
            test = { server ->
                coroutineScope {
                    val enteredRead = CountDownLatch(1)
                    val transport =
                        NamecoinTransport { endpoint, socket ->
                            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5_000)
                            enteredRead.countDown()
                            socket
                        }
                    val call =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            transport.call(server, """{"id":2}""", 2, 60_000)
                        }
                    assertTrue(enteredRead.await(2, TimeUnit.SECONDS))
                    val started = System.nanoTime()
                    withTimeout(1_500) { call.cancelAndJoin() }
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    assertTrue("cancellation took ${elapsed}ms", elapsed < 1_000)
                }
            },
        )

    @Test
    fun callDropsOversizeLine() =
        withSocketServer(
            handler = { socket ->
                socket.getInputStream().bufferedReader().readLine()
                socket.getOutputStream().bufferedWriter().use {
                    it.write("""{"id":2,"result":"""" + "x".repeat(NamecoinTransport.MAX_REPLY_CHARS) + """"}""" + "\n")
                    it.flush()
                }
                Thread.sleep(500)
            },
            test = { server ->
                assertNull(plaintextTransport().call(server, """{"id":2}""", 2, 250))
            },
        )
}
