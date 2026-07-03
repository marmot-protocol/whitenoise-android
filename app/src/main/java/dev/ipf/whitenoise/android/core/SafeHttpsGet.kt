package dev.ipf.whitenoise.android.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.nio.channels.SocketChannel
import java.util.Locale
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The single SSRF-hardened HTTPS GET used by every outbound fetch in the app
 * (NIP-05 resolution, avatar loading, image search). Manual redirect handling
 * re-validates the destination at EVERY hop, because
 * `HttpURLConnection.instanceFollowRedirects = true` would silently follow an
 * `https`→`http` downgrade or a redirect to a private/loopback host.
 *
 * Each hop must satisfy, in order:
 *  - scheme is `https`;
 *  - no embedded credentials (`user:pass@host` can mask the real authority and
 *    leak userinfo to the host);
 *  - the port is the implicit default (`-1`) or an explicit `443` — an explicit
 *    non-standard port is an authority trick and a way to reach an unintended
 *    internal service;
 *  - the host is not a private/loopback literal ([HostSafety.isPrivateOrLoopbackHost]);
 *  - the caller's [hostAllowed] predicate accepts the URL (e.g. a host pin);
 *  - resolve-time DNS-rebinding check: no resolved address is internal.
 *
 * The socket then connects to one of the exact addresses that passed the
 * rebinding check ([openPinnedConnection]): the connection URL is rebuilt
 * around the vetted IP literal so the transport can never re-resolve DNS and
 * be steered to a different answer (the check-then-connect TOCTOU of a
 * short-TTL rebinding attacker, #982). The HTTP `Host` header, TLS SNI, and
 * certificate hostname verification all still use the ORIGINAL hostname —
 * the server certificate is verified against the name the caller asked for,
 * never the IP.
 *
 * The body is bounded regardless of the declared `Content-Length` (which can
 * lie). Returns the raw bytes, or null on any downgrade, disallowed hop,
 * non-2xx, oversize body, or IO error. Callers decode (UTF-8 / bitmap).
 */
object SafeHttpsGet {
    const val DEFAULT_MAX_REDIRECT_HOPS = 5

    private val SENSITIVE_REDIRECT_HEADERS =
        setOf(
            "authorization",
            "cookie",
            "proxy-authorization",
        )

    fun get(
        url: String,
        maxBodyBytes: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        requestHeaders: Map<String, String> = emptyMap(),
        maxRedirectHops: Int = DEFAULT_MAX_REDIRECT_HOPS,
        hostAllowed: (URL) -> Boolean = { true },
    ): ByteArray? {
        val original = runCatching { URL(url) }.getOrNull() ?: return null
        var currentSpec = url
        var hops = 0
        while (true) {
            val parsed = runCatching { URL(currentSpec) }.getOrNull() ?: return null
            if (parsed.protocol?.lowercase(Locale.ROOT) != "https") return null
            val host = parsed.host
            if (host.isNullOrBlank()) return null
            if (!parsed.userInfo.isNullOrEmpty()) return null
            if (parsed.port != -1 && parsed.port != 443) return null
            if (HostSafety.isPrivateOrLoopbackHost(host)) return null
            if (!hostAllowed(parsed)) return null
            val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
            if (resolved.isNullOrEmpty() || resolved.any { HostSafety.isPrivateOrLoopbackAddress(it) }) {
                return null
            }

            val connection =
                openPinnedConnection(
                    parsed = parsed,
                    addresses = resolved,
                    connectTimeoutMillis = connectTimeoutMillis,
                    readTimeoutMillis = readTimeoutMillis,
                    requestHeaders = headersForHop(requestHeaders, original = original, current = parsed),
                ) ?: return null
            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        if (hops >= maxRedirectHops) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        currentSpec = runCatching { URL(parsed, location).toString() }.getOrNull() ?: return null
                        hops += 1
                        // Loop re-validates the post-redirect URL with the same
                        // scheme + host-safety checks as the initial request,
                        // and re-resolves + re-pins the new hop's addresses.
                    }
                    code !in 200..299 -> return null
                    else -> {
                        if (connection.contentLengthLong > maxBodyBytes) return null
                        return connection.inputStream.use { readBounded(it, maxBodyBytes) }
                    }
                }
            } catch (_: IOException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
    }

    internal fun headersForHop(
        requestHeaders: Map<String, String>,
        original: URL,
        current: URL,
    ): Map<String, String> {
        if (sameOrigin(original, current)) return requestHeaders
        return requestHeaders.filterKeys { it.lowercase(Locale.ROOT) !in SENSITIVE_REDIRECT_HEADERS }
    }

    private fun sameOrigin(
        first: URL,
        second: URL,
    ): Boolean =
        first.protocol.equals(second.protocol, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(url: URL): Int =
        when {
            url.port != -1 -> url.port
            url.protocol.equals("https", ignoreCase = true) -> 443
            url.protocol.equals("http", ignoreCase = true) -> 80
            else -> url.defaultPort
        }

    /**
     * Connects pinned to one of the vetted resolved [addresses] so the socket
     * cannot be re-steered by a second, independently-answered DNS resolution.
     * Tries each address in resolver-preference order — a dual-stack host with
     * one unreachable family still connects, mirroring the platform's own
     * fallback — advancing only on a connect-time failure. Returns the
     * connected (request-configured) connection, or null when no vetted
     * address is reachable.
     */
    private fun openPinnedConnection(
        parsed: URL,
        addresses: Array<InetAddress>,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        requestHeaders: Map<String, String>,
    ): HttpURLConnection? {
        for (address in addresses) {
            val connection =
                pinnedConnection(parsed, address, connectTimeoutMillis, readTimeoutMillis, requestHeaders)
                    ?: return null
            try {
                connection.connect()
                return connection
            } catch (_: IOException) {
                connection.disconnect()
            }
        }
        return null
    }

    /**
     * One pinned connection attempt: the URL authority is [address]'s literal
     * (so the transport's own resolution is a parse, never a DNS query), while
     * the HTTP `Host` header, TLS SNI ([SniPinningSslSocketFactory]), and the
     * certificate hostname check ([pinnedHostnameVerifier]) all carry the
     * original hostname.
     */
    private fun pinnedConnection(
        parsed: URL,
        address: InetAddress,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        requestHeaders: Map<String, String>,
    ): HttpsURLConnection? {
        val host = parsed.host ?: return null
        val pinned = pinnedUrl(parsed, address) ?: return null
        val connection =
            (runCatching { pinned.openConnection() }.getOrNull() as? HttpsURLConnection) ?: return null
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        requestHeaders.forEach { (name, value) ->
            connection.setRequestProperty(name, value)
        }
        // The URL authority is the IP literal, so restore the real virtual
        // host at the HTTP layer. Set last so no caller header can shadow it.
        connection.setRequestProperty("Host", host)
        connection.sslSocketFactory = SniPinningSslSocketFactory(connection.sslSocketFactory, host)
        connection.hostnameVerifier = pinnedHostnameVerifier(host)
        return connection
    }

    /**
     * [parsed] with its authority swapped for [address]'s literal, keeping
     * protocol, (already-validated) port, path, and query. IPv6 literals are
     * bracketed; a zone id is dropped (a vetted global address never carries
     * one, and it would not survive URL syntax).
     */
    internal fun pinnedUrl(
        parsed: URL,
        address: InetAddress,
    ): URL? {
        val literal = address.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: return null
        val authority = if (address is Inet6Address) "[$literal]" else literal
        return runCatching { URL(parsed.protocol, authority, parsed.port, parsed.file) }.getOrNull()
    }

    /**
     * TLS hostname verification for a pinned connection. The stack hands the
     * verifier the connection URL's host — here the pinned IP literal — which
     * would reject every legitimate certificate, so it is ignored and the
     * platform's default verifier (OkHostnameVerifier on Android) checks the
     * session's certificate against the ORIGINAL [host] instead. This is the
     * same certificate ↔ hostname binding a non-pinned request gets; the pin
     * changes where the socket dials, never what identity is required.
     */
    internal fun pinnedHostnameVerifier(host: String): HostnameVerifier =
        HostnameVerifier { _, session ->
            HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
        }

    private fun readBounded(
        input: InputStream,
        limit: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** [get] decoded as UTF-8, or null on any failure or oversize body. */
    fun getUtf8(
        url: String,
        maxBodyBytes: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        requestHeaders: Map<String, String> = emptyMap(),
        maxRedirectHops: Int = DEFAULT_MAX_REDIRECT_HOPS,
        hostAllowed: (URL) -> Boolean = { true },
    ): String? =
        get(
            url = url,
            maxBodyBytes = maxBodyBytes,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            requestHeaders = requestHeaders,
            maxRedirectHops = maxRedirectHops,
            hostAllowed = hostAllowed,
        )?.toString(Charsets.UTF_8)
}

/**
 * Layers TLS over the pinned TCP connection using the ORIGINAL hostname, not
 * the IP literal the connection URL carries: the hostname passed to the
 * delegate factory drives SNI and the JSSE peer identity, so the server
 * selects the right certificate and sessions cache under the name. The
 * result is wrapped in [SniPinnedSslSocket] because the platform HTTP stack
 * re-labels the socket with the URL host after this factory returns.
 */
internal class SniPinningSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val hostname: String,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(
        socket: Socket,
        host: String?,
        port: Int,
        autoClose: Boolean,
    ): Socket = pinned(delegate.createSocket(socket, hostname, port, autoClose))

    // The host argument on the non-layering variants is the pinned URL's
    // authority — the vetted IP literal, which the Socket constructors parse
    // without a DNS query — so connect the plain socket there and layer TLS
    // labeled with the real hostname on top.
    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = layered(Socket(host, port), port)

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = layered(Socket(host, port, localHost, localPort), port)

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = layered(Socket(host, port), port)

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = layered(Socket(address, port, localAddress, localPort), port)

    private fun layered(
        plain: Socket,
        port: Int,
    ): Socket = pinned(delegate.createSocket(plain, hostname, port, true))

    private fun pinned(socket: Socket): Socket = (socket as? SSLSocket)?.let { SniPinnedSslSocket(it, hostname) } ?: socket
}

/**
 * Delegating [SSLSocket] that keeps the original hostname authoritative for
 * TLS. Android's `HttpsURLConnection` stack re-labels the socket with the
 * connection URL's host after the socket factory returns
 * (`Platform.configureTlsExtensions` sets `SSLParameters.serverNames` from
 * the URL host and re-applies the parameters) — on a pinned connection that
 * would swap SNI to the IP literal, which Conscrypt then drops from the
 * ClientHello, so a multi-tenant server would present the wrong certificate.
 * Two properties of this wrapper prevent that:
 *  - [setSSLParameters] re-pins the SNI server-name list to the original
 *    hostname before delegating, so re-applied parameters keep ALPN etc. but
 *    cannot clobber the name;
 *  - it is not a Conscrypt class, so the stack's reflective
 *    `setHostname(String)` escape hatch does not exist on it (and the JDK's
 *    equivalent `SSLSocketImpl.setHost` cast fails the same way).
 *
 * Everything else — including [getSession], which the hostname verifier
 * checks the certificate through — delegates verbatim.
 */
internal class SniPinnedSslSocket(
    private val delegate: SSLSocket,
    private val hostname: String,
) : SSLSocket() {
    override fun setSSLParameters(params: SSLParameters) {
        // SNIHostName rejects non-hostname values; if the original hostname
        // is not expressible as SNI, leave the parameters as passed — the TLS
        // provider refuses to send a non-hostname SNI value anyway, which is
        // the pre-pinning behavior, never a weaker identity check.
        runCatching { params.serverNames = listOf(SNIHostName(hostname)) }
        delegate.sslParameters = params
    }

    override fun getSSLParameters(): SSLParameters = delegate.sslParameters

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites

    override fun setEnabledCipherSuites(suites: Array<String>?) {
        delegate.enabledCipherSuites = suites
    }

    override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols

    override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols

    override fun setEnabledProtocols(protocols: Array<String>?) {
        delegate.enabledProtocols = protocols
    }

    override fun getSession(): SSLSession = delegate.session

    override fun getHandshakeSession(): SSLSession? = delegate.handshakeSession

    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {
        delegate.addHandshakeCompletedListener(listener)
    }

    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {
        delegate.removeHandshakeCompletedListener(listener)
    }

    override fun startHandshake() {
        delegate.startHandshake()
    }

    override fun setUseClientMode(mode: Boolean) {
        delegate.useClientMode = mode
    }

    override fun getUseClientMode(): Boolean = delegate.useClientMode

    override fun setNeedClientAuth(need: Boolean) {
        delegate.needClientAuth = need
    }

    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth

    override fun setWantClientAuth(want: Boolean) {
        delegate.wantClientAuth = want
    }

    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth

    override fun setEnableSessionCreation(flag: Boolean) {
        delegate.enableSessionCreation = flag
    }

    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation

    override fun getApplicationProtocol(): String? = delegate.applicationProtocol

    override fun getHandshakeApplicationProtocol(): String? = delegate.handshakeApplicationProtocol

    // Socket surface: the wrapper must proxy the connected delegate, not the
    // unconnected base-class state.
    override fun connect(endpoint: SocketAddress?) {
        delegate.connect(endpoint)
    }

    override fun connect(
        endpoint: SocketAddress?,
        timeout: Int,
    ) {
        delegate.connect(endpoint, timeout)
    }

    override fun bind(bindpoint: SocketAddress?) {
        delegate.bind(bindpoint)
    }

    override fun getInetAddress(): InetAddress? = delegate.inetAddress

    override fun getLocalAddress(): InetAddress = delegate.localAddress

    override fun getPort(): Int = delegate.port

    override fun getLocalPort(): Int = delegate.localPort

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    override fun getChannel(): SocketChannel? = delegate.channel

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun setTcpNoDelay(on: Boolean) {
        delegate.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setSoLinger(
        on: Boolean,
        linger: Int,
    ) {
        delegate.setSoLinger(on, linger)
    }

    override fun getSoLinger(): Int = delegate.soLinger

    override fun sendUrgentData(data: Int) {
        delegate.sendUrgentData(data)
    }

    override fun setOOBInline(on: Boolean) {
        delegate.setOOBInline(on)
    }

    override fun getOOBInline(): Boolean = delegate.getOOBInline()

    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setSendBufferSize(size: Int) {
        delegate.sendBufferSize = size
    }

    override fun getSendBufferSize(): Int = delegate.sendBufferSize

    override fun setReceiveBufferSize(size: Int) {
        delegate.receiveBufferSize = size
    }

    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize

    override fun setKeepAlive(on: Boolean) {
        delegate.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun setTrafficClass(tc: Int) {
        delegate.trafficClass = tc
    }

    override fun getTrafficClass(): Int = delegate.trafficClass

    override fun setReuseAddress(on: Boolean) {
        delegate.reuseAddress = on
    }

    override fun getReuseAddress(): Boolean = delegate.reuseAddress

    override fun close() {
        delegate.close()
    }

    override fun shutdownInput() {
        delegate.shutdownInput()
    }

    override fun shutdownOutput() {
        delegate.shutdownOutput()
    }

    override fun isConnected(): Boolean = delegate.isConnected

    override fun isBound(): Boolean = delegate.isBound

    override fun isClosed(): Boolean = delegate.isClosed

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun setPerformancePreferences(
        connectionTime: Int,
        latency: Int,
        bandwidth: Int,
    ) {
        delegate.setPerformancePreferences(connectionTime, latency, bandwidth)
    }

    override fun toString(): String = delegate.toString()
}
