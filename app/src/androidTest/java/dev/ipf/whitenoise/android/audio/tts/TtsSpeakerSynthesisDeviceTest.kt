package dev.ipf.whitenoise.android.audio.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.maestro.MaestroFixtureRunner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in pronunciation evidence from synthetic controls; no live profile or message is recorded. */
@ManualDeviceFixture
class TtsSpeakerSynthesisDeviceTest {
    /** Captures raw name/punctuation audio for listening; completion does not assert correct pronunciation. */
    @Test
    fun exportSyntheticSpeakerNameAudioControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
        val engine = InstrumentationRegistry.getArguments().getString("ttsEngine").orEmpty()
        assumeTrue("Select an installed engine explicitly", engine.isNotEmpty())
        val ready = CountDownLatch(1)
        val initialized = AtomicInteger(TextToSpeech.ERROR)
        lateinit var tts: TextToSpeech
        instrumentation.runOnMainSync {
            tts =
                TextToSpeech(context, {
                    initialized.set(it)
                    ready.countDown()
                }, engine)
        }
        try {
            assertTrue("Engine initialization timed out", ready.await(30, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, initialized.get())
            assertTrue("English voice is unavailable", tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE)
            captureControls(tts, engine, File(context.getExternalFilesDir(null), "tts-speaker-${UUID.randomUUID()}"))
        } finally {
            tts.shutdown()
        }
    }

    /** Retains both exact input and the resulting platform audio under a unique fixture-only directory. */
    private fun captureControls(
        tts: TextToSpeech,
        engine: String,
        directory: File,
    ) {
        check(directory.mkdirs())
        val controls =
            listOf(
                "Alice: Body.",
                "🚀 Alice: Body.",
                "Al👩🏽‍💻ice: Body.",
                "Alice 🚀: Body.",
                "❤️ Alice: Body.",
                "Alice ❤️: Body.",
                "👩🏽‍💻: Body.",
                "Alice 🚀 Body.",
                "Alice ❤️ Body.",
                "👩🏽‍💻 Body.",
            )
        val utterances = JSONArray()
        controls.forEachIndexed { index, text ->
            val file = File(directory, "control-$index.wav")
            utterances.put(JSONObject().put("text", text).put("file", file.name))
            synthesize(tts, file, text)
        }
        File(directory, "controls.json").writeText(
            JSONObject()
                .put("engine", engine)
                .put("voice", tts.voice?.name)
                .put("locale", tts.voice?.locale?.toLanguageTag())
                .put("utterances", utterances)
                .toString(2),
        )
        Log.i("TtsSyntheticSpeaker", "Synthetic audio controls: ${directory.absolutePath}")
    }

    /** Waits for the file-completion callback; admission alone is not synthesized audio evidence. */
    private fun synthesize(
        tts: TextToSpeech,
        output: File,
        text: String,
    ) {
        val complete = CountDownLatch(1)
        val error = AtomicInteger(TextToSpeech.SUCCESS)
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == output.name) complete.countDown()
                }

                @Deprecated("Required platform callback")
                override fun onError(utteranceId: String?) = onError(utteranceId, TextToSpeech.ERROR)

                override fun onError(
                    utteranceId: String?,
                    errorCode: Int,
                ) {
                    if (utteranceId != output.name) return
                    error.set(errorCode)
                    complete.countDown()
                }
            },
        )
        assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile(text, Bundle(), output, output.name))
        assertTrue("Synthesis timed out for ${output.name}", complete.await(30, TimeUnit.SECONDS))
        assertEquals("Synthesis failed for ${output.name}", TextToSpeech.SUCCESS, error.get())
        assertTrue("No audio for ${output.name}", output.length() > 0L)
    }
}
