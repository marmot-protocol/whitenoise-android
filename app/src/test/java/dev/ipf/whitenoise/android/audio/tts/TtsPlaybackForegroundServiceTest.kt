package dev.ipf.whitenoise.android.audio.tts

import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsPlaybackForegroundServiceTest {
    private class RejectingForegroundStartContext(
        base: Context,
    ) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("blocked")
    }

    private class ServiceHarness {
        val engine = FakeServiceEngine()
        var nextSentences = 0
        var previousSentences = 0
        var stops = 0
        val controller =
            TtsController(
                audioFocus = GrantedFocus(),
                maxChunkLength = 4_000,
            )
        val host =
            object : TtsPlaybackSessionHost {
                override val controller: TtsController get() = this@ServiceHarness.controller

                override fun nextSentence() {
                    nextSentences += 1
                }

                override fun previousSentence() {
                    previousSentences += 1
                }

                override fun stopSession() {
                    stops += 1
                    controller.stop()
                }
            }

        init {
            controller.attachEngine(engine)
        }

        fun speak(text: String = "One. Two.") {
            check(controller.speak(text, Locale.US))
        }
    }

    private fun installHost(): ServiceHarness {
        val installed = ServiceHarness()
        TtsPlaybackForegroundService.hostResolver = { installed.host }
        return installed
    }

    /** Installs a production-shaped host while retaining Robolectric's service lifecycle. */
    private fun installHost(host: TtsPlaybackSessionHost) {
        TtsPlaybackForegroundService.hostResolver = { host }
    }

    @After
    fun restoreResolver() {
        TtsPlaybackForegroundService.hostResolver = defaultResolver
    }

    private companion object {
        // Captured before any test overrides it, so teardown restores the
        // production Application-backed resolver.
        val defaultResolver = TtsPlaybackForegroundService.hostResolver
    }

    @Test
    fun activeSessionRunsInTheForegroundWithAGenericNotification() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        val shadowService = shadowOf(service as Service)
        assertNotNull(shadowService.lastForegroundNotification)
        val notification = shadowService.lastForegroundNotification
        val text =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
        assertTrue("generic title expected, got '$text'", text.isNotBlank())
        assertFalse("notification must not leak message content", text.contains("One"))
        controller.destroy()
    }

    @Test
    fun wordProgressDoesNotRepostTheNotification() {
        val harness = installHost()
        harness.speak("One two three four.")

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val posted = shadowOf(service as Service).lastForegroundNotification
        assertNotNull(posted)

        harness.engine.range(index = 0, start = 0, end = 3)
        harness.engine.range(index = 0, start = 4, end = 7)
        shadowOf(Looper.getMainLooper()).idle()

        // Word progress changes the controller state but nothing this surface
        // shows, so the posted notification must be the same instance.
        assertSame(posted, shadowOf(service as Service).lastForegroundNotification)
        controller.destroy()
    }

    @Test
    fun terminalControllerStateStopsTheService() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        harness.controller.stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(service as Service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun terminalControllerStateStopsObservingBeforeDestruction() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        harness.controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        val terminalNotification = shadowOf(service as Service).lastForegroundNotification

        harness.speak("A replacement session.")
        shadowOf(Looper.getMainLooper()).idle()

        assertSame(terminalNotification, shadowOf(service as Service).lastForegroundNotification)
        controller.destroy()
    }

    @Test
    fun startingWithNoActiveSessionStopsImmediately() {
        installHost()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        val shadowService = shadowOf(service as Service)
        assertNotNull(shadowService.lastForegroundNotification)
        assertTrue(shadowService.isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun platformStartRejectionIsContained() {
        val rejectingContext = RejectingForegroundStartContext(RuntimeEnvironment.getApplication())

        assertFalse(TtsPlaybackForegroundService.start(rejectingContext))
    }

    /** Every notification action is forwarded to the one app-owned playback session. */
    @Test
    fun notificationActionsOperateTheOneSharedSession() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        val context = RuntimeEnvironment.getApplication()
        service.onStartCommand(Intent(context, service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_PAUSE),
            0,
            2,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(harness.controller.state.value is TtsState.Paused)

        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_PLAY),
            0,
            3,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(harness.controller.state.value is TtsState.Speaking)

        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_NEXT_SENTENCE),
            0,
            4,
        )
        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_PREVIOUS_SENTENCE),
            0,
            5,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, harness.nextSentences)
        assertEquals(1, harness.previousSentences)

        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_STOP),
            0,
            6,
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, harness.stops)
        assertTrue(harness.controller.state.value is TtsState.Idle)
        assertTrue(shadowOf(service as Service).isStoppedBySelf)
        controller.destroy()
    }

    /**
     * Proves notification and platform MediaSession actions cross the real
     * history/session boundary at sentence granularity, including paused
     * navigation and sender announcement on a message crossing.
     */
    @Test
    fun systemMediaActionsNavigateTheRealHistorySessionBySentence() =
        runTest {
            val harness = SessionHarness(this)
            harness.pager.loaded += harness.record("m1", sentences = 3)
            harness.pager.loaded += harness.record("m2", sentences = 2)
            harness.speakEntries(listOf(harness.entry("m1", sentences = 3), harness.entry("m2", sentences = 2)))
            val realHost =
                object : TtsPlaybackSessionHost {
                    override val controller = harness.controller

                    override fun nextSentence() = harness.session.nextSentence()

                    override fun previousSentence() = harness.session.previousSentence()

                    override fun stopSession() {
                        harness.controller.stop()
                        harness.session.onSessionCleared()
                    }
                }
            installHost(realHost)

            val serviceController = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
            val service = serviceController.get()
            val context = RuntimeEnvironment.getApplication()
            service.onStartCommand(Intent(context, service::class.java), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()

            service.onStartCommand(
                Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_NEXT_SENTENCE),
                0,
                2,
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertCursor(harness.controller.state.value, messageIndex = 0, sentenceIndex = 1)

            val notification = shadowOf(service as Service).lastForegroundNotification
            assertEquals("Previous sentence", notification.actions[0].title.toString())
            assertEquals("Next sentence", notification.actions[2].title.toString())
            val transport = TtsPlaybackMediaSessionCallback(realHost)
            transport.onSkipToNext()
            assertCursor(harness.controller.state.value, messageIndex = 0, sentenceIndex = 2)

            transport.onSkipToNext()
            assertCursor(harness.controller.state.value, messageIndex = 1, sentenceIndex = 0)
            assertTrue(harness.spokenTexts().any { it == "Nm2: Text m2." })

            harness.controller.pause()
            val spokenBeforePausedNavigation = harness.engine.spoken.size
            val focusAcquiresBeforePausedNavigation = harness.focus.acquires
            transport.onSkipToPrevious()

            val paused = harness.controller.state.value
            assertTrue(paused is TtsState.Paused)
            assertCursor(paused, messageIndex = 0, sentenceIndex = 2)
            assertEquals(spokenBeforePausedNavigation, harness.engine.spoken.size)
            assertEquals(focusAcquiresBeforePausedNavigation, harness.focus.acquires)
            serviceController.destroy()
        }

    /** Asserts the public transport cursor without coupling to engine chunk indices. */
    private fun assertCursor(
        state: TtsState,
        messageIndex: Int,
        sentenceIndex: Int,
    ) {
        assertEquals(messageIndex, state.messageIndex)
        assertEquals(sentenceIndex, state.sentenceIndexWithinMessage)
    }

    @Test
    fun pauseKeepsTheServiceAndItsNotificationAlive() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        val context = RuntimeEnvironment.getApplication()
        service.onStartCommand(Intent(context, service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_PAUSE),
            0,
            2,
        )
        shadowOf(Looper.getMainLooper()).idle()

        // A paused session does not expire: the service stays up with a
        // dismissible (non-ongoing) notification offering Play.
        val shadowService = shadowOf(service as Service)
        assertFalse(shadowService.isStoppedBySelf)
        val notification = shadowService.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(0, notification.flags and android.app.Notification.FLAG_ONGOING_EVENT)
        controller.destroy()
    }

    @Test
    fun taskRemovalStopsTheSessionExactlyOnce() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        service.onTaskRemoved(null)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, harness.stops)
        assertTrue(harness.controller.state.value is TtsState.Idle)
        controller.destroy()
        assertEquals(1, harness.stops)
    }

    @Test
    fun activeServiceDestructionStopsTheSession() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        controller.destroy()

        assertEquals(1, harness.stops)
        assertTrue(harness.controller.state.value is TtsState.Idle)
    }

    @Test
    fun notificationDismissalStopsTheSession() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        val context = RuntimeEnvironment.getApplication()
        service.onStartCommand(Intent(context, service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        service.onStartCommand(
            Intent(context, service::class.java).setAction(TtsPlaybackForegroundService.ACTION_DISMISS),
            0,
            2,
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, harness.stops)
        assertTrue(shadowOf(service as Service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun playbackChannelIsRegistered() {
        val harness = installHost()
        harness.speak()

        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(RuntimeEnvironment.getApplication(), service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        val manager = RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel("read_aloud_playback"))
        controller.destroy()
    }

    private class GrantedFocus : TtsAudioFocus {
        override fun acquire(
            onFocusLoss: () -> Unit,
            onOwnerSurrender: () -> Unit,
        ): Boolean = true

        override fun release() = Unit
    }

    private class FakeServiceEngine : TtsSpeechEngine {
        private var doneCallback: ((String?) -> Unit)? = null
        private var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null
        private val spoken = mutableListOf<String>()

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setSpeechRate(rate: Float) = Unit

        override fun setCallbacks(
            onStart: (String?) -> Unit,
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
            onRangeStart: (String?, Int, Int, Int) -> Unit,
            onStop: (String?, Boolean) -> Unit,
        ) {
            doneCallback = onDone
            rangeCallback = onRangeStart
        }

        override fun clearCallbacks() {
            doneCallback = null
            rangeCallback = null
        }

        /** Reports a spoken word range for the utterance at [index], as an engine does. */
        fun range(
            index: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[index], start, end, 0)
        }

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += utteranceId
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit
    }
}
