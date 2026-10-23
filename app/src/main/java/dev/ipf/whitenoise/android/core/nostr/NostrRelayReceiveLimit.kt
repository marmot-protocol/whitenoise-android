package dev.ipf.whitenoise.android.core.nostr

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.Socket
import okio.Source
import java.io.EOFException
import java.net.ProtocolException

private const val HTTP_SWITCHING_PROTOCOLS = 101

// Safe UTF-8 allowance for the existing 256 Ki UTF-16-character check.
private const val MAX_RELAY_MESSAGE_BYTES = 1024 * 1024L

/** Install on every public-relay client, including clients injected into the query adapter. */
internal fun OkHttpClient.Builder.limitNostrRelayMessages(): OkHttpClient.Builder {
    addInterceptor(NostrRelayReceiveLimitInterceptor())
    return this
}

internal class NostrRelayReceiveLimitInterceptor(
    private val maxMessageBytes: Long = MAX_RELAY_MESSAGE_BYTES,
) : Interceptor {
    init {
        require(maxMessageBytes > 0)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        // OkHttp adds this offer before application interceptors run. A wire-byte
        // limit cannot bound inflation, so do not negotiate message compression.
        val request =
            chain
                .request()
                .newBuilder()
                .removeHeader("Sec-WebSocket-Extensions")
                .build()
        val response = chain.proceed(request)
        if (response.code != HTTP_SWITCHING_PROTOCOLS) return response
        val socket = response.socket
        if (socket == null) {
            response.close()
            throw ProtocolException("Relay upgrade has no socket")
        }
        if (response.headers.values("Sec-WebSocket-Extensions").any(String::isNotBlank)) {
            socket.cancel()
            response.close()
            throw ProtocolException("Relay message extensions are unsupported")
        }
        val limitedSocket =
            object : Socket by socket {
                override val source: Source = BoundedRelayMessageSource(socket.source, maxMessageBytes)
            }
        return response.newBuilder().socket(limitedSocket).build()
    }
}

/**
 * A receive allocation boundary, not a replacement WebSocket parser. Header
 * lengths are checked before payload bytes reach OkHttp's message buffer. The
 * library still owns handshake validation, UTF-8, control handling and delivery.
 */
internal class BoundedRelayMessageSource(
    delegate: Source,
    private val maxMessageBytes: Long = MAX_RELAY_MESSAGE_BYTES,
) : ForwardingSource(delegate) {
    private val header = ByteArray(MAX_HEADER_BYTES)
    private var headerRead = 0
    private var headerSize = BASE_HEADER_BYTES
    private var payloadRemaining = 0L
    private var messageBytes = 0L
    private var messageOpen = false
    private var endsMessage = false

    init {
        require(maxMessageBytes > 0)
    }

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0)
        return when {
            byteCount == 0L -> 0
            payloadRemaining == 0L -> readHeader(sink, byteCount)
            else -> {
                val count = super.read(sink, minOf(byteCount, payloadRemaining, MAX_READ_BYTES))
                if (count == -1L) throw EOFException("Truncated relay message")
                payloadRemaining -= count
                if (payloadRemaining == 0L) finishFrame()
                count
            }
        }
    }

    private fun readHeader(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val offset = sink.size
        val count = super.read(sink, minOf(byteCount, (headerSize - headerRead).toLong()))
        if (count == -1L) {
            if (headerRead != 0 || messageOpen) throw EOFException("Truncated relay frame")
            return -1
        }
        repeat(count.toInt()) { index -> header[headerRead++] = sink[offset + index] }
        if (headerRead == BASE_HEADER_BYTES) {
            headerSize =
                when (header[1].toInt() and LENGTH_MASK) {
                    LENGTH_16 -> BASE_HEADER_BYTES + 2
                    LENGTH_64 -> MAX_HEADER_BYTES
                    else -> BASE_HEADER_BYTES
                }
        }
        if (headerRead == headerSize) admitFrame()
        return count
    }

    private fun admitFrame() {
        val flags = header[0].toInt() and BYTE_MASK
        val opcode = flags and OPCODE_MASK
        val isFinal = flags and FINAL_FLAG != 0
        validateFrameFlags(flags)
        val length = payloadLength()
        when (opcode) {
            CONTINUATION -> {
                requireMessageState(expectedOpen = true)
                admitData(length, isFinal)
            }
            TEXT, BINARY -> {
                requireMessageState(expectedOpen = false)
                messageOpen = true
                admitData(length, isFinal)
            }
            CLOSE, PING, PONG -> {
                if (!isFinal || length > MAX_CONTROL_BYTES) throw ProtocolException("Invalid relay control frame")
                endsMessage = false
            }
            else -> throw ProtocolException("Unsupported relay frame opcode")
        }
        payloadRemaining = length
        headerRead = 0
        headerSize = BASE_HEADER_BYTES
        if (payloadRemaining == 0L) finishFrame()
    }

    private fun validateFrameFlags(flags: Int) {
        if (flags and RESERVED_FLAGS != 0 || header[1].toInt() and MASK_FLAG != 0) {
            throw ProtocolException("Unsupported relay frame flags")
        }
    }

    private fun requireMessageState(expectedOpen: Boolean) {
        if (messageOpen != expectedOpen) {
            throw ProtocolException(if (expectedOpen) "Unexpected relay continuation" else "Unfinished relay message")
        }
    }

    private fun payloadLength(): Long {
        val marker = header[1].toInt() and LENGTH_MASK
        if (marker < LENGTH_16) return marker.toLong()
        if (header[2].toInt() and MASK_FLAG != 0 && marker == LENGTH_64) {
            throw ProtocolException("Invalid relay frame length")
        }
        var length = 0L
        for (index in BASE_HEADER_BYTES until headerSize) {
            length = (length shl BITS_PER_BYTE) or (header[index].toLong() and BYTE_MASK.toLong())
        }
        if (length < LENGTH_16 || marker == LENGTH_64 && length <= MAX_LENGTH_16) {
            throw ProtocolException("Non-minimal relay frame length")
        }
        return length
    }

    private fun admitData(
        length: Long,
        isFinal: Boolean,
    ) {
        if (length > maxMessageBytes - messageBytes) throw ProtocolException("Relay message exceeds receive limit")
        messageBytes += length
        endsMessage = isFinal
    }

    private fun finishFrame() {
        if (endsMessage) {
            messageOpen = false
            messageBytes = 0
        }
    }

    private companion object {
        const val BITS_PER_BYTE = 8
        const val MAX_READ_BYTES = 8192L
        const val BASE_HEADER_BYTES = 2
        const val MAX_HEADER_BYTES = 10
        const val BYTE_MASK = 0xff
        const val LENGTH_MASK = 0x7f
        const val OPCODE_MASK = 0x0f
        const val FINAL_FLAG = 0x80
        const val MASK_FLAG = 0x80
        const val RESERVED_FLAGS = 0x70
        const val LENGTH_16 = 126
        const val LENGTH_64 = 127
        const val MAX_LENGTH_16 = 65535L
        const val MAX_CONTROL_BYTES = 125
        const val CONTINUATION = 0
        const val TEXT = 1
        const val BINARY = 2
        const val CLOSE = 8
        const val PING = 9
        const val PONG = 10
    }
}
