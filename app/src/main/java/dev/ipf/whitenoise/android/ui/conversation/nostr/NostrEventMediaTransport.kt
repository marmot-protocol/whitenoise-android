package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.HostSafety
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Platform streaming adapter: every request and redirect remains on a validated public HTTPS path. */
internal val nostrMediaHttpClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(PublicMediaDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Player backpressure can keep a streaming call open throughout playback.
        .callTimeout(2, TimeUnit.HOURS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(::nostrMediaResponse)
        .build()
}

internal fun nostrMediaResponse(chain: Interceptor.Chain): Response {
    var request = chain.request()
    var redirects = 0
    while (true) {
        requireSafeMediaDestination(request.url)
        val response = chain.proceed(request)
        if (!response.isRedirect) return boundedMediaResponse(response)
        val target = response.header("Location")?.let(request.url::resolve)
        response.close()
        if (redirects == MAX_MEDIA_REDIRECTS || target == null) {
            throw IOException("Unsafe or excessive media redirect")
        }
        redirects++
        request = request.newBuilder().url(target).build()
    }
}

private fun requireSafeMediaDestination(url: HttpUrl) {
    if (!url.isSafeMediaDestination()) throw IOException("Unsafe media destination")
}

internal fun boundedMediaResponse(
    response: Response,
    maximumBytes: Long = MAX_MEDIA_RESPONSE_BYTES,
): Response {
    val body = response.body ?: return response
    if (body.contentLength() > maximumBytes) {
        response.close()
        throw IOException("Media response exceeds its budget")
    }
    val bounded =
        object : ResponseBody() {
            private val limited =
                object : ForwardingSource(body.source()) {
                    private var received = 0L

                    override fun read(
                        sink: okio.Buffer,
                        byteCount: Long,
                    ): Long {
                        val read = super.read(sink, minOf(byteCount, maximumBytes - received + 1))
                        if (read > 0) received += read
                        if (received > maximumBytes) throw IOException("Media response exceeds its budget")
                        return read
                    }
                }.buffer()

            override fun contentType() = body.contentType()

            override fun contentLength() = body.contentLength()

            override fun source(): BufferedSource = limited
        }
    return response.newBuilder().body(bounded).build()
}

internal fun HttpUrl.isSafeMediaDestination(): Boolean =
    when {
        !isHttps -> false
        port != STANDARD_HTTPS_PORT -> false
        encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty() -> false
        else -> !HostSafety.isPrivateOrLoopbackHost(host)
    }

private object PublicMediaDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (HostSafety.isPrivateOrLoopbackHost(hostname)) throw UnknownHostException("Unsafe media host")
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(HostSafety::isPrivateOrLoopbackAddress)) {
            throw UnknownHostException("Unsafe media address")
        }
        return addresses
    }
}

private const val MAX_MEDIA_REDIRECTS = 5
private const val STANDARD_HTTPS_PORT = 443
private const val MAX_MEDIA_RESPONSE_BYTES = 512L * 1024 * 1024
