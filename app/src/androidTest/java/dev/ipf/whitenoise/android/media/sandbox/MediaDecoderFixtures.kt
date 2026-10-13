package dev.ipf.whitenoise.android.media.sandbox

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** The existing Apache-2.0 AndroidX Media fixture is shared with the video regression suites. */
internal fun verifiedVideoFixture(): ByteArray {
    val encoded =
        InstrumentationRegistry
            .getInstrumentation()
            .context.assets
            .open("silent-black-10s.mp4.b64")
            .bufferedReader()
            .use { it.readText() }
    val decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(decoded)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    assertEquals("83fbcd994ece32535285a0ea6505c681cb96c471736308316eabda90cced9f51", digest)
    return decoded
}

/** A local 200ms, 16kHz mono PCM signal; no microphone, account or network fixture. */
internal fun pcmWaveFixture(): ByteArray {
    val samples = 3200
    val dataBytes = samples * 2
    return ByteBuffer
        .allocate(44 + dataBytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(16000)
            putInt(32000)
            putShort(2.toShort())
            putShort(16.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
            repeat(samples) { putShort((if (it % 2 == 0) 8000 else -8000).toShort()) }
        }.array()
}
