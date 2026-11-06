package dev.ipf.whitenoise.android.state

import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Bounded loopback-only encrypted-blob store for generated emoji interoperability tests. */
internal class EmojiBlossomFixture : Closeable {
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val blobs = ConcurrentHashMap<String, ByteArray>()
    val url = "http://127.0.0.1:${server.localPort}"
    private val failure = AtomicReference<Throwable?>()
    private val worker =
        thread(name = "emoji-blossom-fixture", isDaemon = true) {
            runCatching { serve() }.onFailure { failure.set(it) }
        }

    /** Serves one bounded request per connection; closing this fixture interrupts its accept loop. */
    private fun serve() {
        while (!server.isClosed) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val input = DataInputStream(socket.getInputStream())
                    val request = line(input).split(' ')
                    val headers = headers(input)
                    val method = request.first()
                    val output = socket.getOutputStream()
                    if (headers["expect"]?.equals("100-continue", true) == true) {
                        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                        output.flush()
                    }
                    val bytes =
                        if (method == "PUT") {
                            upload(input, headers)
                        } else {
                            blobs[request[1].substringAfterLast('/').substringBefore('?')]
                        }
                    val code = if (bytes == null) "404 Not Found" else "200 OK"
                    output.write("HTTP/1.1 $code\r\nContent-Length: ${bytes?.size ?: 0}\r\nConnection: close\r\n\r\n".toByteArray())
                    if (method != "HEAD" && bytes != null) output.write(bytes)
                    output.flush()
                }
            } catch (_: SocketException) {
                check(server.isClosed)
            }
        }
    }

    /** Keeps duplicate header names from bypassing the fixture's finite header count. */
    private fun headers(input: DataInputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var count = 0
        var header = line(input)
        while (header.isNotEmpty()) {
            check(++count <= 64)
            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            header = line(input)
        }
        return headers
    }

    /** Retains only a bounded encrypted upload and returns the corresponding Blossom descriptor. */
    private fun upload(
        input: DataInputStream,
        headers: Map<String, String>,
    ): ByteArray {
        val length = requireNotNull(headers["content-length"]).toInt()
        require(length in 1..(2 * 1024 * 1024))
        val body = ByteArray(length).also(input::readFully)
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        blobs[hash] = body
        return JSONObject()
            .put("url", "$url/$hash")
            .put("sha256", hash)
            .put("size", length)
            .put("type", "application/octet-stream")
            .put("uploaded", 1)
            .toString()
            .toByteArray()
    }

    /** Reads a finite HTTP header line without buffering past the encrypted request body. */
    private fun line(input: DataInputStream): String {
        val value = StringBuilder()
        while (true) {
            val byte = input.readUnsignedByte()
            if (byte == 10) return value.toString().trimEnd('\r')
            check(value.length < 8192)
            value.append(byte.toChar())
        }
    }

    /** Releases the listener and only this fixture's generated encrypted data. */
    override fun close() {
        server.close()
        worker.join(12_000)
        check(!worker.isAlive)
        blobs.clear()
        failure.get()?.let { throw AssertionError("generated Blossom fixture failed", it) }
    }
}
