package dev.ipf.whitenoise.android.ui.conversation.nostr

import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

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
    fun declaredAndStreamedResponseBudgetsAreBothEnforced() {
        val request = Request.Builder().url("https://media.example/video").build()

        fun response(body: ResponseBody) =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        assertThrows(IOException::class.java) {
            boundedMediaResponse(response("12345".toResponseBody()), maximumBytes = 4)
        }
        val streamed =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = -1

                override fun source() = Buffer().writeUtf8("12345")
            }
        boundedMediaResponse(response(streamed), maximumBytes = 4).use { bounded ->
            assertThrows(IOException::class.java) { bounded.body!!.string() }
        }
    }
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
