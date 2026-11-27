package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/** The catalogue connection opens only when used, reads voices once ready, and closes with its engine handle. */
@RunWith(RobolectricTestRunner::class)
class TtsVoiceCatalogueTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val offlineVoice = Voice("Offline", Locale.US, 300, 100, false, emptySet())
    private val connections = mutableListOf<CatalogueTextToSpeech>()
    private var initStatus: Int? = TextToSpeech.SUCCESS
    private val factory =
        TtsFactory { _, listener, engine ->
            assertEquals("engine.a", engine)
            CatalogueTextToSpeech(listener).also { connection ->
                connections += connection
                initStatus?.let(listener::onInit)
            }
        }

    /** A handle that never checks a voice never opens a second connection, and releasing it opens none. */
    @Test
    fun unusedHandleNeverOpensACatalogueConnection() {
        handle().release()

        assertTrue(connections.isEmpty())
    }

    /** The catalogue connection opens on first use and shuts down with its handle. */
    @Test
    fun catalogueConnectionClosesWithItsHandle() {
        val handle = handle()

        assertEquals(listOf(offlineVoice), handle.voiceCatalogue?.voices()?.toList())
        handle.release()

        assertEquals(1, connections.size)
        assertTrue(connections.single().shutDown)
    }

    /** A connection that never finishes starting, or fails to, yields no catalogue rather than blocking forever. */
    @Test
    fun unreadyOrFailedConnectionYieldsNoCatalogue() {
        initStatus = null
        assertNull(ConnectedTtsVoiceCatalogue(context, factory, "engine.a", readyTimeoutMs = 10).voices())

        initStatus = TextToSpeech.ERROR
        assertNull(ConnectedTtsVoiceCatalogue(context, factory, "engine.a").voices())
    }

    /** A released connection is never read again. */
    @Test
    fun releasedConnectionIsNotRead() {
        val catalogue = ConnectedTtsVoiceCatalogue(context, factory, "engine.a")
        catalogue.release()

        assertNull(catalogue.voices())
        assertEquals(0, connections.single().reads)
    }

    /** A playback handle for engine.a whose catalogue connection comes from [factory]. */
    private fun handle() =
        TtsEngineHandle(
            textToSpeech = CatalogueTextToSpeech {},
            enginePackage = "engine.a",
            trust = EngineTrust.Local,
            openVoiceCatalogue = { ConnectedTtsVoiceCatalogue(context, factory, "engine.a") },
        )

    /** A connection that lists one installed voice and records reads and shutdown. */
    private inner class CatalogueTextToSpeech(
        listener: OnInitListener,
    ) : TextToSpeech(RuntimeEnvironment.getApplication(), listener) {
        var reads = 0
            private set
        var shutDown = false
            private set

        /** Counts the catalogue read. */
        override fun getVoices(): MutableSet<Voice> {
            reads++
            return mutableSetOf(offlineVoice)
        }

        /** Records the shutdown without a framework connection. */
        override fun shutdown() {
            shutDown = true
        }
    }
}
