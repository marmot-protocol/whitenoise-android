package dev.ipf.whitenoise.android.core.namecoin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/**
 * One-shot ElectrumX JSON-RPC over a raw TLS socket and newline-delimited JSON.
 *
 * TLS endpoints either use the normal system CA/path/hostname checks or a
 * per-endpoint leaf-SPKI pin. The pin never leaks between operators. IP entries
 * retain their operator hostname for SNI and hostname verification.
 *
 * Socket reads are real blocking I/O on [Dispatchers.IO]. Coroutine cancellation
 * closes the socket immediately, including during connect, TLS handshake, or a
 * stalled read, rather than merely waiting for the read timeout to expire.
 */
internal open class NamecoinTransport(
    private val connectSocket: (NamecoinServer, Socket) -> Socket = ::connectTls,
) {
    /** An ElectrumX endpoint. [pinBase64] is the base64 SHA-256 of its leaf SPKI. */
    internal data class NamecoinServer(
        val host: String,
        val port: Int,
        val pinBase64: String? = null,
        val tlsHostname: String = host,
    )

    /** Trust only a leaf whose SubjectPublicKeyInfo SHA-256 equals one of [pinsBase64]. */
    internal class PinnedPublicKeyTrustManager(
        private val pinsBase64: Set<String>,
    ) : X509TrustManager {
        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].publicKey.encoded)
            val presented =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(digest)
            if (presented !in pinsBase64) {
                throw CertificateException("leaf public key does not match the pinned SHA-256")
            }
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ): Unit = throw CertificateException("client certificates not supported")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Send one request and return the first newline-delimited JSON object whose
     * top-level numeric id matches [wantId]. Notifications and oversized lines
     * are ignored. Transport errors and timeouts return null; caller cancellation
     * remains cancellation and closes the underlying socket.
     */
    open suspend fun call(
        server: NamecoinServer,
        requestJson: String,
        wantId: Int,
        timeoutMillis: Long,
    ): String? =
        try {
            withTimeoutOrNull(timeoutMillis) {
                awaitReply(server, requestJson, wantId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }

    private suspend fun awaitReply(
        server: NamecoinServer,
        requestJson: String,
        wantId: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val rawSocket = Socket()
                val socketRef = AtomicReference<Socket>(rawSocket)

                // Registered before connect, TLS, or stream I/O. Closing a Socket
                // unblocks threads blocked in its input/output streams.
                continuation.invokeOnCancellation {
                    runCatching { socketRef.get().close() }
                }

                try {
                    val socket = connectSocket(server, rawSocket)
                    socketRef.set(socket)
                    socket.use { connected ->
                        val writer = connected.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        writer.write(requestJson)
                        writer.newLine()
                        writer.flush()

                        val reader = connected.getInputStream().bufferedReader(Charsets.UTF_8)
                        while (continuation.isActive) {
                            val line = reader.readLine() ?: break
                            if (line.length <= MAX_REPLY_CHARS && replyMatchesId(line, wantId)) {
                                continuation.resume(line)
                                return@suspendCancellableCoroutine
                            }
                        }
                        if (continuation.isActive) continuation.resume(null)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    internal companion object {
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 15_000

        /** Reply bound, matching the upstream shim's 128 KiB caller buffer. */
        const val MAX_REPLY_CHARS = 128 * 1024

        internal fun replyMatchesId(
            body: String,
            wantId: Int,
        ): Boolean {
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return false
            val id = obj.opt("id") as? Number ?: return false
            return id.toLong() == wantId.toLong()
        }

        internal fun trustManagerFor(server: NamecoinServer): X509TrustManager? = server.pinBase64?.let { PinnedPublicKeyTrustManager(setOf(it)) }

        private fun connectTls(
            server: NamecoinServer,
            raw: Socket,
        ): Socket {
            try {
                // SO_TIMEOUT must be installed before connect/TLS setup so the
                // handshake and every subsequent blocking read share the bound.
                raw.soTimeout = READ_TIMEOUT_MILLIS
                raw.connect(InetSocketAddress(server.host, server.port), CONNECT_TIMEOUT_MILLIS)

                val pinnedTrustManager = trustManagerFor(server)
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(
                    null,
                    pinnedTrustManager?.let { arrayOf(it) },
                    java.security.SecureRandom(),
                )
                val tls =
                    sslContext.socketFactory.createSocket(
                        raw,
                        server.tlsHostname,
                        server.port,
                        true,
                    ) as SSLSocket
                tls.soTimeout = READ_TIMEOUT_MILLIS
                tls.sslParameters =
                    tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                tls.startHandshake()
                return tls
            } catch (error: Throwable) {
                runCatching { raw.close() }
                throw error
            }
        }
    }
}
