package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidTtsSpeechEngineTest {
    @Test
    fun forwardsValidOnRangeStartCallbacks() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        var received: Triple<String?, Int, Int>? = null
        engine.setCallbacks(
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { utteranceId, start, end -> received = Triple(utteranceId, start, end) },
        )

        tts.listener?.onRangeStart("whitenoise.tts.1.0", 4, 9, 0)

        assertEquals(Triple("whitenoise.tts.1.0", 4, 9), received)
    }

    @Test
    fun clearCallbacksRemovesTheUtteranceListener() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        engine.setCallbacks(onDone = {}, onError = { _, _ -> }, onRangeStart = { _, _, _ -> })

        engine.clearCallbacks()

        assertNull(tts.listener)
    }

    private class CapturingTextToSpeech(
        context: Context,
    ) : TextToSpeech(context, {}) {
        var listener: UtteranceProgressListener? = null
            private set

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
            this.listener = listener
            return super.setOnUtteranceProgressListener(listener)
        }
    }
}
