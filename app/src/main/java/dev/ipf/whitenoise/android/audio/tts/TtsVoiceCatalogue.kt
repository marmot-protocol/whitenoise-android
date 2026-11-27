package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Reads a speech engine's current voices for the per-sentence offline-voice check. */
fun interface TtsVoiceCatalogue {
    /** The engine's voices now, or null when they cannot be read. May block, so call it off the main thread. */
    fun voices(): Collection<Voice>?
}

/**
 * A second connection to the playback engine, used only to read its voice catalogue; it never receives text. Android
 * runs one call at a time per [TextToSpeech] instance and a catalogue read is a long binder call, so reading over its
 * own connection keeps it from holding up the playback connection's stop and language change.
 */
class ConnectedTtsVoiceCatalogue(
    context: Context,
    factory: TtsFactory,
    enginePackage: String,
    private val readyTimeoutMs: Long = CATALOGUE_READY_TIMEOUT_MS,
) : TtsVoiceCatalogue {
    private val ready = CountDownLatch(1)

    @Volatile
    private var usable = false

    @Volatile
    private var released = false

    private val connection: TextToSpeech? =
        runCatching {
            factory.create(
                context,
                { status ->
                    usable = status == TextToSpeech.SUCCESS
                    ready.countDown()
                },
                enginePackage,
            )
        }.getOrNull()

    /** Waits a bounded time for the connection, then reads its catalogue; null when it is not usable. */
    override fun voices(): Collection<Voice>? {
        val tts = connection?.takeIf { ready.await(readyTimeoutMs, TimeUnit.MILLISECONDS) && usable && !released }
        return tts?.let { runCatching { it.voices }.getOrNull() }
    }

    /** Closes the connection with the playback engine it belongs to. */
    fun release() {
        released = true
        connection?.shutdown()
    }

    private companion object {
        const val CATALOGUE_READY_TIMEOUT_MS = 5_000L
    }
}
