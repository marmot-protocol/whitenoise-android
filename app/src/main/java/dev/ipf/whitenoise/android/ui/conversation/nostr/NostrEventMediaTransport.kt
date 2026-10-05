package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.core.HostSafety
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Platform streaming adapter: every request and redirect remains on a validated public HTTPS path. */
internal val nostrMediaHttpClient: OkHttpClient by lazy {
    OkHttpClient
        .Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(PublicMediaDns())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Media3 buffers the stream and cancels it with player lifecycle; idle reads remain bounded.
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
        if (!response.isRedirect) return response
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

internal fun HttpUrl.isSafeMediaDestination(): Boolean =
    when {
        !isHttps -> false
        port != STANDARD_HTTPS_PORT -> false
        encodedUsername.isNotEmpty() || encodedPassword.isNotEmpty() -> false
        else -> !HostSafety.isPrivateOrLoopbackHost(host)
    }


private const val MAX_MEDIA_REDIRECTS = 5
private const val STANDARD_HTTPS_PORT = 443
