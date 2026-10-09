package dev.ipf.whitenoise.android.state

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Disposable in-process loopback Nostr relay for generated test identities, a Kotlin port of
 * `tools/attachment-fixture/fixture_relay.py`. It answers EVENT/REQ/CLOSE over WebSocket. Closing it
 * takes every client offline, which is how the cold-start test models a process that relaunches without
 * network.
 */
internal class LoopbackNostrRelay : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val lock = Any()
    private val events = LinkedHashMap<String, JSONObject>()
    private val clients = ConcurrentHashMap.newKeySet<Client>()

    /** Test-controlled visibility models an advertised outbox without a discovery-only package. */
    @Volatile var hiddenKinds: Set<Int> = emptySet()

    /** Counts exact kind reads so recovery tests can prove the actual route used. */
    val kindReads = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()

    /** Returns immutable copies of fixture events without exposing user-owned storage. */
    fun recordedEvents(kind: Int): List<JSONObject> =
        synchronized(lock) {
            events.values.filter { it.optInt("kind") == kind }.map { JSONObject(it.toString()) }
        }

    /** The relay's WebSocket endpoint. */
    val url: String = "ws://$LOOPBACK:${server.localPort}"

    init {
        thread(name = "loopback-relay-accept", isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "loopback-relay-client", isDaemon = true) { serve(socket) }
            }
        }
    }

    /** Stops accepting and drops every open connection. */
    override fun close() {
        runCatching { server.close() }
        clients.forEach(Client::close)
        clients.clear()
    }

    /** Upgrades one accepted connection to the WebSocket relay, or rejects a plain HTTP request. */
    private fun serve(socket: Socket) {
        socket.use {
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()
            input.readHttpLine() ?: return
            val headers = generateSequence { input.readHttpLine()?.takeIf(String::isNotEmpty) }.toList()
            when (val key = headers.headerValue("sec-websocket-key")) {
                null -> output.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                else -> Client(socket, input, output).run(key)
            }
        }
    }

    /** One upgraded WebSocket connection with its own subscriptions. */
    private inner class Client(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: OutputStream,
    ) : Closeable {
        private val subscriptions = ConcurrentHashMap<String, List<JSONObject>>()

        /** Completes the handshake, then handles frames until the peer closes. */
        fun run(key: String) {
            val accept = MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray())
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: ${Base64.encodeToString(accept, Base64.NO_WRAP)}\r\n\r\n"
                ).toByteArray(),
            )
            output.flush()
            clients += this
            try {
                readFrames()
            } catch (_: IOException) {
                // A closed relay or client ends this connection.
            } finally {
                clients -= this
            }
        }

        /** Reads masked client frames, answering pings and dispatching text messages. */
        private fun readFrames() {
            while (true) {
                val first = input.readUnsignedByte()
                val second = input.readUnsignedByte()
                val size =
                    when (val length = second and LENGTH_MASK) {
                        LENGTH_16 -> input.readUnsignedShort().toLong()
                        LENGTH_64 -> input.readLong()
                        else -> length.toLong()
                    }
                check(size in 0..MAX_FRAME) { "fixture frame too large" }
                val mask = ByteArray(MASK_BYTES).also(input::readFully)
                val body = ByteArray(size.toInt()).also(input::readFully)
                body.indices.forEach { body[it] = (body[it].toInt() xor mask[it % MASK_BYTES].toInt()).toByte() }
                when (first and OPCODE_MASK) {
                    OPCODE_CLOSE -> return send(ByteArray(0), OPCODE_CLOSE)
                    OPCODE_PING -> send(body, OPCODE_PONG)
                    OPCODE_TEXT -> handle(JSONArray(String(body)))
                }
            }
        }

        /** Handles EVENT, REQ and CLOSE; this fixture deliberately has no authentication. */
        private fun handle(message: JSONArray) {
            when (message.getString(0)) {
                "EVENT" -> {
                    val event = message.getJSONObject(1)
                    synchronized(lock) {
                        events[event.getString("id")] = event
                        clients.forEach { client -> runCatching { client.deliver(event) } }
                    }
                    sendJson(
                        JSONArray()
                            .put("OK")
                            .put(event.getString("id"))
                            .put(true)
                            .put(""),
                    )
                }
                "REQ" -> {
                    val id = message.getString(1)
                    val filters = (2 until message.length()).map(message::getJSONObject)
                    filters.forEach { filter ->
                        val kinds = filter.optJSONArray("kinds")
                        if (kinds != null) {
                            for (index in 0 until kinds.length()) {
                                kindReads
                                    .computeIfAbsent(kinds.getInt(index)) {
                                        java.util.concurrent.atomic
                                            .AtomicInteger()
                                    }.incrementAndGet()
                            }
                        }
                    }
                    synchronized(lock) {
                        subscriptions[id] = filters
                        stored(filters).forEach { sendJson(JSONArray().put("EVENT").put(id).put(it)) }
                        sendJson(JSONArray().put("EOSE").put(id))
                    }
                }
                "CLOSE" -> subscriptions.remove(message.getString(1))
            }
        }

        /** Stored events matching any filter, newest first and within each filter's limit. */
        private fun stored(filters: List<JSONObject>): Collection<JSONObject> {
            val newestFirst = events.values.sortedByDescending { it.optLong("created_at") }
            val selected = LinkedHashMap<String, JSONObject>()
            filters.forEach { filter ->
                newestFirst
                    .filter { it.optInt("kind") !in hiddenKinds && matches(it, filter) }
                    .take(filter.optInt("limit", Int.MAX_VALUE))
                    .forEach { selected[it.getString("id")] = it }
            }
            return selected.values
        }

        /** Pushes a newly accepted event to each matching live subscription. */
        fun deliver(event: JSONObject) {
            if (event.optInt("kind") in hiddenKinds) return
            subscriptions.forEach { (id, filters) ->
                if (filters.any { matches(event, it) }) sendJson(JSONArray().put("EVENT").put(id).put(event))
            }
        }

        /** Sends one JSON text frame. */
        private fun sendJson(value: JSONArray) = send(value.toString().toByteArray(), OPCODE_TEXT)

        /** Serializes writes so live events never interleave with subscription replies. */
        private fun send(
            body: ByteArray,
            opcode: Int,
        ) {
            val frame = ByteArrayOutputStream()
            frame.write(FIN or opcode)
            when {
                body.size < LENGTH_16 -> frame.write(body.size)
                body.size <= UShort.MAX_VALUE.toInt() -> {
                    frame.write(LENGTH_16)
                    frame.write(body.size shr Byte.SIZE_BITS)
                    frame.write(body.size and BYTE_MASK)
                }
                else -> {
                    frame.write(LENGTH_64)
                    (Long.SIZE_BYTES - 1 downTo 0).forEach { frame.write((body.size.toLong() shr (it * 8)).toInt()) }
                }
            }
            frame.write(body)
            synchronized(this) {
                output.write(frame.toByteArray())
                output.flush()
            }
        }

        /** Drops the connection. */
        override fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 16
        const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_FRAME = 2L * 1024 * 1024
        const val MASK_BYTES = 4
        const val FIN = 0x80
        const val OPCODE_MASK = 0x0f
        const val OPCODE_TEXT = 1
        const val OPCODE_CLOSE = 8
        const val OPCODE_PING = 9
        const val OPCODE_PONG = 10
        const val LENGTH_MASK = 0x7f
        const val LENGTH_16 = 126
        const val LENGTH_64 = 127
        const val BYTE_MASK = 0xff

        /** Applies the Nostr filter fields generated identities use, as `fixture_relay.py` does. */
        @Suppress("ReturnCount")
        fun matches(
            event: JSONObject,
            filter: JSONObject,
        ): Boolean {
            val scalar = mapOf("ids" to "id", "authors" to "pubkey", "kinds" to "kind")
            for ((field, eventField) in scalar) {
                val allowed = filter.optJSONArray(field) ?: continue
                val value = event.opt(eventField)?.toString()
                if ((0 until allowed.length()).none { allowed.get(it).toString() == value }) return false
            }
            val createdAt = event.optLong("created_at")
            if (createdAt < filter.optLong("since", 0L) || createdAt > filter.optLong("until", Long.MAX_VALUE)) {
                return false
            }
            return filter.keys().asSequence().filter { it.startsWith("#") }.all { key ->
                val values = filter.getJSONArray(key).let { array -> (0 until array.length()).map(array::getString) }
                val tags = event.optJSONArray("tags") ?: JSONArray()
                (0 until tags.length()).map(tags::getJSONArray).any { tag ->
                    tag.length() > 1 && tag.getString(0) == key.drop(1) && tag.getString(1) in values
                }
            }
        }

        /** Reads one CRLF-terminated header line, or null at end of stream. */
        fun DataInputStream.readHttpLine(): String? {
            val line = StringBuilder()
            while (true) {
                val next = read()
                if (next < 0) return line.takeIf { it.isNotEmpty() }?.toString()
                if (next == '\n'.code) return line.toString().trimEnd('\r')
                line.append(next.toChar())
            }
        }

        /** Case-insensitive header lookup. */
        fun List<String>.headerValue(name: String): String? =
            firstNotNullOfOrNull { header ->
                header.substringBefore(':').trim().takeIf { it.equals(name, ignoreCase = true) }?.let {
                    header.substringAfter(':').trim()
                }
            }
    }
}
