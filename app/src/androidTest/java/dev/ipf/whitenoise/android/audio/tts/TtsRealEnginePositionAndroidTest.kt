package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in physical-engine evidence, separate from deterministic callback injection. */
@RunWith(AndroidJUnit4::class)
class TtsRealEnginePositionAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun selectedInstalledEngineProducesMappedWordsOrTheEstimatedFallback() {
        val args = InstrumentationRegistry.getArguments()
        val packageName = args.getString("ttsEngine").orEmpty()
        assumeTrue("This case requires an explicitly selected installed speech engine", packageName.isNotEmpty())
        val expectedRanges = requireNotNull(args.getString("ttsExpectedRanges")).toBooleanStrict()
        EngineProbe(packageName).use { probe ->
            probe.speak()
            probe.verify(expectedRanges)
        }
    }
}

private class EngineProbe(
    private val packageName: String,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ready = CountDownLatch(1)
    private val initialized = AtomicInteger(TextToSpeech.ERROR)
    private lateinit var tts: TextToSpeech
    private val controller = TtsController(TtsAudioFocusOwner(instrumentation.targetContext), maxChunkLength = 4_000)
    private val stateJob = Job()
    private val words = CopyOnWriteArrayList<TtsVisibleTextSpan>()
    private val complete = CountDownLatch(1)
    private val nativeRanges = AtomicInteger()
    private val failures = AtomicInteger()
    private val text = "Café readers consider liberty, privacy and voluntary cooperation every day."

    init {
        instrumentation.runOnMainSync {
            tts =
                TextToSpeech(instrumentation.targetContext, {
                    initialized.set(it)
                    ready.countDown()
                }, packageName)
        }
    }

    fun speak() {
        assertTrue("Engine initialization exceeded 30 seconds", ready.await(30, TimeUnit.SECONDS))
        assertEquals(TextToSpeech.SUCCESS, initialized.get())
        controller.attachEngine(recordingEngine(), packageName)
        CoroutineScope(stateJob + Dispatchers.Unconfined).launch {
            controller.state.collect { words.addAll(it.passage?.visibleWord.orEmpty()) }
        }
        assertTrue(controller.speak(listOf(entry()), Locale.US))
    }

    fun verify(expectedRanges: Boolean) {
        assertTrue("Engine did not finish within 60 seconds", complete.await(60, TimeUnit.SECONDS))
        assertEquals("Engine reported a synthesis failure", 0, failures.get())
        assertEquals("Observed engine capability must match the recorded fixture", expectedRanges, nativeRanges.get() > 0)
        assertTrue("No visible words were produced", words.isNotEmpty())
        assertTrue(words.all { it.leafId == "body" && it.start >= 0 && it.end <= text.length })
        assertTrue("Sender narration must not displace body coordinates", words.any { text.substring(it.start, it.end) == "Café" })
    }

    private fun recordingEngine(): TtsSpeechEngine {
        val adapter = AndroidTtsSpeechEngine(tts, packageName)
        return object : TtsSpeechEngine by adapter {
            override fun setCallbacks(
                onStart: (String?) -> Unit,
                onDone: (String?) -> Unit,
                onError: (String?, Int) -> Unit,
                onRangeStart: (String?, Int, Int, Int) -> Unit,
                onStop: (String?, Boolean) -> Unit,
            ) {
                adapter.setCallbacks(
                    onStart,
                    {
                        onDone(it)
                        complete.countDown()
                    },
                    { id, error ->
                        failures.incrementAndGet()
                        onError(id, error)
                        complete.countDown()
                    },
                    { id, start, end, frame ->
                        nativeRanges.incrementAndGet()
                        onRangeStart(id, start, end, frame)
                    },
                    onStop,
                )
            }
        }
    }

    private fun entry() =
        TtsSpeakableEntry(
            senderKey = "author",
            senderDisplayName = "Grace",
            text = text,
            messageIdHex = "engine-position-fixture",
            spokenTextSpans =
                listOf(
                    TtsSpokenTextSpan(
                        TtsTextRange(0, text.length),
                        TtsVisibleTextSpan("body", 0, text.length),
                    ),
                ),
            visibleLeaves = mapOf("body" to text),
            sourceText = text,
        )

    override fun close() {
        stateJob.cancel()
        controller.stop()
        controller.detachEngine()
        tts.shutdown()
    }
}
