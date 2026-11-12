package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.ui.conversation.messages.forceUsableTtsResolutionForTests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Exercises admission and delayed commit through the shipping automatic playback methods. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsAutoReadAdmissionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeSessionEngine()
    private lateinit var app: WhiteNoiseAppState
    private val entries = listOf(TtsSpeakableEntry("private", "Sender", "Private message."))

    @Before
    fun prepare() {
        context
            .getSharedPreferences(TtsWarningPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        app =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(NoOpDraftPersistence),
                accountIdHexResolver = { null },
                accounts = listOf(AccountSummaryFfi("account", "aa", true, false, false, true, false)),
                activeAccountRef = "account",
            )
        app.ttsController.attachEngine(engine)
        app.forceUsableTtsResolutionForTests()
    }

    @After
    fun release() = app.stopSpeaking()

    @Test
    fun heldMicrophoneRefusesAutomaticSpeechWithoutReleasingCapture() {
        val microphone = Any()
        assertTrue(app.microphoneCaptureCoordinator.tryAcquire(microphone))
        assertFalse(app.speakAloudAutoRead("group", entries, Locale.US))
        assertTrue(app.microphoneCaptureCoordinator.isOwnedBy(microphone))
        assertTrue(engine.spoken.isEmpty())
        app.microphoneCaptureCoordinator.release(microphone)
        assertTrue(app.speakAloudAutoRead("group", entries, Locale.US))
    }

    @Test
    fun unknownEngineRequiresItsOwnAcknowledgement() {
        app.forceUsableTtsResolutionForTests(EngineTrust.Unknown, "unknown.one")
        assertFalse(app.speakAloudAutoRead("group", entries, Locale.US))
        app.ttsWarningPreferences.acknowledge("unknown.one")
        assertTrue(app.speakAloudAutoRead("group", entries, Locale.US))
        app.stopSpeaking()
        app.forceUsableTtsResolutionForTests(EngineTrust.Unknown, "unknown.two")
        assertFalse(app.speakAloudAutoRead("group", entries, Locale.US))
    }

    @Test
    fun microphoneAcquiredDuringPreparationRejectsCommit() =
        runBlocking {
            val microphone = Any()
            var checks = 0
            val started =
                app.speakAloudAutoRead(
                    "group",
                    entries,
                    Locale.US,
                    backgroundPreparation = true,
                    canCommit = {
                        checks++
                        if (checks == 3) app.microphoneCaptureCoordinator.tryAcquire(microphone)
                        true
                    },
                )
            assertTrue(checks >= 3)
            assertFalse(started)
            assertTrue(engine.spoken.isEmpty())
            assertTrue(app.microphoneCaptureCoordinator.isOwnedBy(microphone))
            app.microphoneCaptureCoordinator.release(microphone)
        }

    @Test
    fun engineConsentIsRecheckedAfterPreparation() =
        runBlocking {
            var checks = 0
            val started =
                app.speakAloudAutoRead(
                    "group",
                    entries,
                    Locale.US,
                    backgroundPreparation = true,
                    canCommit = {
                        checks++
                        if (checks == 3) app.forceUsableTtsResolutionForTests(EngineTrust.Unknown, "unacknowledged")
                        true
                    },
                )
            assertTrue(checks >= 3)
            assertFalse(started)
            assertTrue(engine.spoken.isEmpty())
        }

    private object NoOpDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(key: String, value: String?) = Unit
    }
}
