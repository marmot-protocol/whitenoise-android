package dev.ipf.whitenoise.android.ui.conversation.nostr

import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

class NostrEventMediaTransportTest {
    @Test
    fun unsafeRedirectsFailBeforeTheDestinationIsRequested() {
        listOf(
            "http://media.example/video",
            "https://127.0.0.1/video",
            "https://[::1]/video",
            "https://media.example:8443/video",
            "https://user:pass@media.example/video",
        ).forEach { destination ->
            val chain = FakeChain(listOf(destination))
            assertThrows(IOException::class.java) { chain.execute() }
            assertEquals(1, chain.requests.size)
        }
    }

    @Test
    fun safeRedirectIsFollowedButMissingLocationAndExcessiveHopsFail() {
        val safe = FakeChain(listOf("https://images.example/video"))
        safe.execute().use { assertEquals("ok", it.body!!.string()) }
        assertEquals(2, safe.requests.size)
        val missing = FakeChain(listOf(null))
        assertThrows(IOException::class.java) { missing.execute() }
        val excessive = FakeChain(List(7) { "https://media.example/hop$it" })
        assertThrows(IOException::class.java) { excessive.execute() }
        assertEquals(6, excessive.requests.size)
    }

    @Test
    fun unsafeInitialRequestIsRejectedWithoutCallingTheChain() {
        val chain = FakeChain(emptyList(), "https://127.0.0.1/video")
        assertThrows(IOException::class.java) { chain.execute() }
        assertEquals(0, chain.requests.size)
    }

    @Test
    fun playerClientUsesExplicitRedirectsPublicDnsAndNoProxy() {
        assertEquals(Proxy.NO_PROXY, nostrMediaHttpClient.proxy)
        assertFalse(nostrMediaHttpClient.followRedirects)
        assertFalse(nostrMediaHttpClient.followSslRedirects)
        assertTrue(nostrMediaHttpClient.dns is PublicMediaDns)
        assertEquals(0, nostrMediaHttpClient.callTimeoutMillis)
        assertEquals(30_000, nostrMediaHttpClient.readTimeoutMillis)
    }

    @Test
    fun dnsRejectsPrivateLoopbackAndMixedAnswers() {
        val public = address(8, 8, 8, 8)
        val private = address(10, 0, 0, 1)
        val loopback = address(127, 0, 0, 1)
        listOf(emptyList(), listOf(private), listOf(loopback), listOf(public, private)).forEach { answers ->
            val dns = PublicMediaDns(Dns { answers })
            assertThrows(UnknownHostException::class.java) { dns.lookup("media.example") }
        }
        assertEquals(listOf(public), PublicMediaDns(Dns { listOf(public) }).lookup("media.example"))
    }

    @Test
    fun privateHostnameIsRejectedBeforeDnsLookup() {
        var calls = 0
        val dns =
            PublicMediaDns(
                Dns {
                    calls++
                    listOf(address(8, 8, 8, 8))
                },
            )
        assertThrows(UnknownHostException::class.java) { dns.lookup("localhost") }
        assertEquals(0, calls)
    }

    @Test
    fun largeProgressiveRangesRemainLazyAndKeepTheirRangeHeader() {
        listOf(null, "bytes=0-", "bytes=700000000-").forEach { range ->
            var sourceCalls = 0
            val body =
                object : ResponseBody() {
                    override fun contentType(): MediaType? = null

                    override fun contentLength(): Long = 4L * 1024 * 1024 * 1024

                    override fun source(): Buffer {
                        sourceCalls++
                        return Buffer().writeUtf8("sample")
                    }
                }
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(::nostrMediaResponse)
                    .addInterceptor { chain ->
                        assertEquals(range, chain.request().header("Range"))
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(if (range == null) 200 else 206)
                            .message("stream fixture")
                            .body(body)
                            .build()
                    }.build()
            val request =
                Request
                    .Builder()
                    .url("https://media.example/video")
            range?.let { request.header("Range", it) }
            client.newCall(request.build()).execute().use { response ->
                assertEquals(0, sourceCalls)
                assertEquals(4L * 1024 * 1024 * 1024, response.body!!.contentLength())
                assertEquals("sample", response.body!!.source().readUtf8(6))
            }
        }
    }

    private fun address(vararg bytes: Int): InetAddress = InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
}

/** Exercises the real OkHttp application chain; the scripted final interceptor never dials. */
private class FakeChain(
    private val redirects: List<String?>,
    initialUrl: String = "https://media.example/video",
) {
    private val initial = Request.Builder().url(initialUrl).build()
    val requests = mutableListOf<Request>()
    private val client =
        OkHttpClient
            .Builder()
            .addInterceptor(::nostrMediaResponse)
            .addInterceptor { chain ->
                val request = chain.request()
                val index = requests.size
                requests += request
                val response =
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (index < redirects.size) 302 else 200)
                        .message("fixture")
                        .body("ok".toResponseBody())
                redirects.getOrNull(index)?.let { response.header("Location", it) }
                response.build()
            }.build()

    fun execute(): Response = client.newCall(initial).execute()
}
