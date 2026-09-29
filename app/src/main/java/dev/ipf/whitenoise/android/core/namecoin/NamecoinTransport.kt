package dev.ipf.whitenoise.android.core.namecoin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

/**
 * Namecoin ElectrumX one-shot JSON-RPC transport over WebSocket Secure,
 * ported from `app/nc_shim.c` (whitenoise-linux commits 79447095f2 and
 * 271f0a4a04) onto OkHttp.
 *
 * One call = one RPC = one socket, matching the C shim: the resolver issues a
 * few calls per name (server.version, blockchain.scripthash.get_history,
 * blockchain.transaction.get, optionally blockchain.headers.subscribe) and the
 * per-call timeout is the resolver's only fallback signal.
 *
 * TLS trust = pinned leaf public key SHA-256 equality (the OkHttp analogue of
 * CURLOPT_PINNEDPUBLICKEY), NOT the system CA chain: the public Namecoin
 * ElectrumX operators ship self-signed certs by convention, and one pinned
 * host serves a leaf whose CN does not match its deploy hostname. The
 * [PinnedPublicKeyTrustManager] accepts a server only when its leaf
 * certificate's SubjectPublicKeyInfo SHA-256 equals the pin, and the
 * [HostnameVerifier] is disabled for the same reason — the pin IS the
 * identity check, so a rotated cert or MITM fails the handshake before any
 * data flows.
 *
 * Hardening carried over from the C shim:
 *  - untrusted frame-length arithmetic is delegated to OkHttp's mature RFC
 *    6455 reader (it validates and reassembles frames); the message-level cap
 *    the C shim enforced in its frame loop is applied here before JSON parse
 *    ([MAX_REPLY_CHARS]) so a hostile server cannot pin us parsing garbage;
 *  - the reply is trusted only after a REAL JSON parse of the envelope
 *    ([replyMatchesId]): a message counts as the reply only when its
 *    top-level `"id"` is a Number equal to the request id. This is strictly
 *    stronger than the shim's depth-1 byte walker, whose only job was to
 *    filter notifications before the Odin-side re-parse;
 *  - every call is bounded by an overall coroutine timeout.
 */
internal class NamecoinTransport(
    private val clientFor: (NamecoinServer) -> OkHttpClient = ::defaultClientFor,
) {
    /** A pinned ElectrumX endpoint. [pinBase64] is the base64 SHA-256 of the leaf SPKI. */
    internal data class NamecoinServer(
        val host: String,
        val port: Int,
        val path: String = "/",
        val pinBase64: String,
    ) {
        val tlsUrl: String
            get() {
                val normalizedPath = if (path.startsWith("/")) path else "/$path"
                return "wss://$host:$port$normalizedPath"
            }
    }

    /**
     * Trust manager that trusts NOTHING the system CA store says and accepts a
     * chain only when the leaf certificate's SubjectPublicKeyInfo SHA-256 is
     * in [pinsBase64]. This is the exact trust posture of libcurl's
     * CURLOPT_PINNEDPUBLICKEY with peer/host verification off.
     */
    internal class PinnedPublicKeyTrustManager(
        private val pinsBase64: Set<String>,
    ) : X509TrustManager {
        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
        ) {
            if (chain.isEmpty()) throw CertificateException("empty certificate chain")
            val leafSpki = chain[0].publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(leafSpki)
            val presented = java.util.Base64.getEncoder().encodeToString(digest)
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
     * Do one ElectrumX JSON-RPC call against [server], sending [requestJson]
     * (which must already carry the id [wantId]) and returning the first reply
     * body whose top-level `"id"` matches. Returns null on dial/handshake
     * failure, timeout, oversize-only replies, or socket close without a
     * matching reply. Server pushes (subscription notifications, replies with
     * other ids) are skipped until the call deadline.
     */
    suspend fun call(
        server: NamecoinServer,
        url: String,
        requestJson: String,
        wantId: Int,
        timeoutMillis: Long,
    ): String? =
        try {
            withTimeout(timeoutMillis) {
                awaitReply(server, url, requestJson, wantId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: Exception) {
            null
        }

    private suspend fun awaitReply(
        server: NamecoinServer,
        url: String,
        requestJson: String,
        wantId: Int,
    ): String? =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val socket = AtomicReference<WebSocket?>()

            fun finish(result: String?) {
                if (!completed.compareAndSet(false, true)) return
                runCatching { socket.get()?.close(NORMAL_CLOSE_CODE, "done") }
                if (!continuation.isActive) return
                continuation.resume(result)
            }

            val listener =
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(requestJson)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        // The C shim's frame-loop cap: refuse to parse a
                        // message beyond the reply bound and keep waiting for
                        // the real reply inside the call deadline.
                        if (text.length > MAX_REPLY_CHARS) return
                        if (replyMatchesId(text, wantId)) finish(text)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) = Unit

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        finish(null)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        finish(null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        finish(null)
                    }
                }

            val request =
                try {
                    Request.Builder().url(url).build()
                } catch (error: IllegalArgumentException) {
                    finish(null)
                    return@suspendCancellableCoroutine
                }
            val createdSocket = clientFor(server).newWebSocket(request, listener)
            socket.set(createdSocket)
            if (completed.get()) {
                runCatching { createdSocket.close(NORMAL_CLOSE_CODE, "done") }
            }
            continuation.invokeOnCancellation {
                if (!completed.compareAndSet(false, true)) return@invokeOnCancellation
                runCatching { socket.get()?.cancel() }
            }
        }

    internal companion object {
        const val NORMAL_CLOSE_CODE = 1_000

        /** Reply bound, matching the C shim's 128 KiB caller buffer. */
        const val MAX_REPLY_CHARS = 128 * 1024

        /**
         * True when [body] is a JSON object whose top-level `"id"` is a Number
         * equal to [wantId]. Replaces the shim's depth-1 byte walker with a
         * real JSON parse at the RPC boundary: nested `"id"` keys and `"id"`
         * inside strings can never match.
         */
        internal fun replyMatchesId(
            body: String,
            wantId: Int,
        ): Boolean {
            val obj = runCatching { JSONObject(body) }.getOrNull() ?: return false
            val id = obj.opt("id") as? Number ?: return false
            return id.toLong() == wantId.toLong()
        }

        /**
         * Build the per-server client: wss:// goes through the pinning trust
         * manager (and ONLY it — no CA fallback); ws:// is accepted for tests
         * against a plaintext mock. readTimeout is 0 because OkHttp requires
         * that for WebSockets; every call carries its own coroutine timeout.
         */
        private fun defaultClientFor(server: NamecoinServer): OkHttpClient {
            val builder =
                OkHttpClient
                    .Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val sslContext = SSLContext.getInstance("TLS")
            val trustManager = PinnedPublicKeyTrustManager(setOf(server.pinBase64))
            sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pinned operators serve self-signed certs whose CN does not
            // necessarily match the deploy hostname (documented for
            // electrumx.testls.space upstream). The leaf-pubkey pin is the
            // identity check, so hostname verification is off — the same
            // posture as CURLOPT_SSL_VERIFYHOST=0 + CURLOPT_PINNEDPUBLICKEY.
            builder.hostnameVerifier { _, _ -> true }
            return builder.build()
        }

        private const val CONNECT_TIMEOUT_SECONDS = 10L
    }
}
