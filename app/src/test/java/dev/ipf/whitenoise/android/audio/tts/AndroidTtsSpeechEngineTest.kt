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
    fun progressListenerForwardsRangesStopsCompletionAndDetailedErrors() {
        val calls = mutableListOf<String>()
        val listener =
            androidTtsProgressListener(
                onDone = { calls += "done:$it" },
                onError = { id, code -> calls += "error:$id:$code" },
                onRangeStart = { id, start, end, frame -> calls += "range:$id:$start:$end:$frame" },
                onStop = { id, interrupted -> calls += "stop:$id:$interrupted" },
            )

        listener.onRangeStart("u1", 2, 7, 11)
        listener.onStop("u1", true)
        listener.onDone("u1")
        listener.onError("u1", TextToSpeech.ERROR_NETWORK)

        assertEquals(
            listOf("range:u1:2:7:11", "stop:u1:true", "done:u1", "error:u1:${TextToSpeech.ERROR_NETWORK}"),
            calls,
        )
    }

    @Test
    fun completionWithoutARangeStillForwardsDone() {
        val calls = mutableListOf<String>()
        val listener = listener(calls)

        listener.onDone("u1")

        assertEquals(listOf("done:u1"), calls)
    }

    @Test
    fun stopWithoutDoneDoesNotSynthesizeCompletion() {
        val calls = mutableListOf<String>()
        val listener = listener(calls)

        listener.onStop("u1", true)

        assertEquals(listOf("stop:u1:true"), calls)
    }

    @Test
    fun adapterForwardsRangeFrame() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        var received: String? = null
        engine.setCallbacks(
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { id, start, end, frame -> received = "$id:$start:$end:$frame" },
            onStop = { _, _ -> },
        )

        tts.listener?.onRangeStart("whitenoise.tts.1.0", 4, 9, 12)

        assertEquals("whitenoise.tts.1.0:4:9:12", received)
    }

    @Test
    fun clearCallbacksRemovesTheUtteranceListener() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        engine.setCallbacks(
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { _, _, _, _ -> },
            onStop = { _, _ -> },
        )

        engine.clearCallbacks()

        assertNull(tts.listener)
    }

    private fun listener(calls: MutableList<String>) =
        androidTtsProgressListener(
            onDone = { calls += "done:$it" },
            onError = { id, code -> calls += "error:$id:$code" },
            onRangeStart = { id, start, end, frame -> calls += "range:$id:$start:$end:$frame" },
            onStop = { id, interrupted -> calls += "stop:$id:$interrupted" },
        )

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
