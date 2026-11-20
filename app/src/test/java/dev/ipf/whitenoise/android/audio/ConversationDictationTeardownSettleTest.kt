package dev.ipf.whitenoise.android.audio

import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationControllerTest.Companion.ACCOUNT
import dev.ipf.whitenoise.android.audio.ConversationDictationControllerTest.Companion.GROUP
import dev.ipf.whitenoise.android.audio.ConversationDictationControllerTest.Companion.key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Restart pacing after a recognizer teardown, and the content-free finish timing it reports. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationTeardownSettleTest {
    private val harness = ConversationDictationControllerTest()

    private fun callerAudioFixture() =
        harness.fixture(draft = TextFieldValue("")).also {
            it.platform.pendingCallerAudio = true
            it.controller.requestStart(ACCOUNT, GROUP, it.drafts.getValue(key()))
        }

    private fun finishTimingEvents(): List<Map<String, Any>> =
        ShadowLog
            .getLogsForTag("WNDictation")
            .mapNotNull { DictationDiagnosticSchema.fields(it.msg) }
            .filter { it["event"] == "finish_timing" }

    /** A two-chunk paste drain waits only the provider-ended settle and reports it once, without content. */
    @Test
    fun twoChunkDrainUsesShortSettleAndReportsFinishTimingOnce() {
        val fixture = callerAudioFixture()
        ShadowLog.clear()
        fixture.controller.paste()
        fixture.scheduler.sleepWithoutDispatch(200L)
        fixture.platform.listener.onResult("first")
        fixture.scheduler.advanceBy(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS - 1L)
        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("second")

        assertEquals("first second", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        val timing = finishTimingEvents().single()
        assertEquals("paste", timing["action"])
        assertEquals(250L, timing["tap_to_last_result_ms"])
        assertEquals(250L, timing["tap_to_delivered_ms"])
        assertEquals(1L, timing["generations_after_tap"])
        assertEquals(0L, timing["retries_after_tap"])
        assertEquals(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS, timing["settle_wait_ms"])
        assertTrue(timing.keys.none { it == "text" || it == "transcript" })
    }

    /** A cancelled generation never had a provider terminal, so a fresh start waits the full settle. */
    @Test
    fun cancelTeardownStillWaitsFullSettleBeforeNextGeneration() {
        val fixture = callerAudioFixture()
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(ConversationDictationController.SUCCESS_RESULT_RESTART_DELAY_MILLIS)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.controller.cancel()
        fixture.controller.requestStart(ACCOUNT, GROUP, fixture.drafts.getValue(key()))

        fixture.scheduler.advanceBy(ConversationDictationController.GENERATION_RESTART_DELAY_MILLIS - 1L)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(3, fixture.platform.sessions.size)
    }

    /** A processing timeout tears the recognizer down itself; its manual retry keeps the full settle. */
    @Test
    fun timeoutTeardownStillWaitsFullSettleBeforeRetry() {
        val fixture = callerAudioFixture()
        fixture.platform.listener.onResult("body")
        fixture.scheduler.runDelay(ConversationDictationController.SUCCESS_RESULT_RESTART_DELAY_MILLIS)
        fixture.controller.paste()
        fixture.scheduler.advanceBy(90_000L)
        assertTrue(fixture.controller.state is ConversationDictationState.Failed)
        val sessions = fixture.platform.sessions.size
        fixture.controller.retry()

        fixture.scheduler.advanceBy(ConversationDictationController.GENERATION_RESTART_DELAY_MILLIS - 1L)
        assertEquals(sessions, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(sessions + 1, fixture.platform.sessions.size)
    }

    /** Busy after the short restart keeps capacity backoff and retries the same chunk exactly once. */
    @Test
    fun busyAfterFastRestartRetriesSameChunkOnceAndDeliversOnce() {
        val fixture = callerAudioFixture()
        ShadowLog.clear()
        fixture.controller.paste()
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS)
        val busy = fixture.platform.session
        fixture.platform.listener.onError(ConversationDictationFailure.RecognizerBusy)

        assertEquals(1, busy.retriedCallerAudio)
        assertEquals(0, busy.acknowledgedCallerAudio)
        assertEquals(0, fixture.scheduler.liveTaskCount(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS))
        fixture.scheduler.runDelay(ConversationDictationController.PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS.first())
        assertEquals(3, fixture.platform.sessions.size)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("tail")

        assertEquals("first tail", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertEquals(3, fixture.platform.sessions.size)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
        val timing = finishTimingEvents().single()
        assertEquals(2L, timing["generations_after_tap"])
        assertEquals(1L, timing["retries_after_tap"])
        assertEquals(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS, timing["settle_wait_ms"])
    }

    /** Confirmed silence while still listening keeps the full restart delay; only a finish drain is shortened. */
    @Test
    fun confirmedSilenceWhileListeningKeepsFullRestart() {
        val fixture = callerAudioFixture()
        val silent = fixture.platform.sessions.single()
        silent.callerAudioHasSpeech = false
        fixture.platform.pendingCallerAudio = false
        silent.providerError(ConversationDictationFailure.NoSpeech)

        assertEquals(1, silent.acknowledgedCallerAudio)
        assertEquals(0, fixture.scheduler.liveTaskCount(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS))
        fixture.platform.pendingCallerAudio = true
        fixture.scheduler.advanceBy(ConversationDictationController.GENERATION_RESTART_DELAY_MILLIS - 1L)
        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(2, fixture.platform.sessions.size)
    }

    /** A silent chunk with more audio queued behind it while listening still waits the full settle. */
    @Test
    fun confirmedSilenceWithQueuedAudioWhileListeningKeepsFullRestart() {
        val fixture = callerAudioFixture()
        val silent = fixture.platform.sessions.single()
        silent.callerAudioHasSpeech = false
        silent.providerError(ConversationDictationFailure.NoSpeech)

        assertEquals(0, fixture.scheduler.liveTaskCount(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS))
        fixture.scheduler.advanceBy(ConversationDictationController.GENERATION_RESTART_DELAY_MILLIS - 1L)
        assertEquals(1, fixture.platform.sessions.size)
        fixture.scheduler.advanceBy(1L)
        assertEquals(2, fixture.platform.sessions.size)
    }

    /** A local pipe failure after a short settle is charged normally instead of spending the disconnect fallback. */
    @Test
    fun pipeFailureAfterShortSettleKeepsItsRetryCost() {
        val fixture = callerAudioFixture()
        fixture.controller.paste()
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS)
        assertEquals(2, fixture.platform.sessions.size)
        val report = mainThreadDictationCallerAudioFailure(fixture.platform.listener) { true }
        report(ConversationDictationCallerAudioFailure.PipeFailed)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fixture.controller.recognition.failureAttempts)
    }

    /** A disconnect right after a short settle costs no retry attempt and restores the full settle afterwards. */
    @Test
    fun disconnectAfterShortSettleFallsBackToFullSettle() {
        val fixture = callerAudioFixture()
        fixture.controller.paste()
        fixture.platform.listener.onResult("first")
        fixture.scheduler.runDelay(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS)
        assertEquals(2, fixture.platform.sessions.size)
        fixture.platform.listener.onError(ConversationDictationFailure.ProviderDisconnected)

        assertEquals(0, fixture.controller.recognition.failureAttempts)
        assertEquals(0, fixture.scheduler.liveTaskCount(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS))
        fixture.scheduler.runDelay(ConversationDictationController.PROVIDER_DISCONNECT_RETRY_DELAYS_MILLIS.first())
        assertEquals(3, fixture.platform.sessions.size)
        fixture.platform.listener.onResult("second")
        assertEquals(0, fixture.scheduler.liveTaskCount(PROVIDER_ENDED_TEARDOWN_SETTLE_MILLIS))
        fixture.scheduler.runDelay(ConversationDictationController.GENERATION_RESTART_DELAY_MILLIS)
        assertEquals(4, fixture.platform.sessions.size)
        fixture.platform.pendingCallerAudio = false
        fixture.platform.listener.onResult("tail")

        assertEquals("first second tail", fixture.drafts.getValue(key()).text)
        assertEquals(1, fixture.writes)
        assertTrue(fixture.controller.state is ConversationDictationState.Idle)
    }
}
