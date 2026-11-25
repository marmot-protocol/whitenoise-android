package dev.ipf.whitenoise.android.audio.tts

import android.app.Service
import android.content.Intent
import android.os.Looper
import dev.ipf.whitenoise.android.notifications.QueuedStartShadowService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Read-aloud teardown stops by start id so an early stop never cancels or demotes a newer start. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [QueuedStartShadowService::class])
class TtsPlaybackForegroundStopTest {
    /** Restores the production host resolver so each case starts isolated. */
    @After
    fun restoreResolver() {
        TtsPlaybackForegroundService.hostResolver = defaultResolver
    }

    /** Missing host and observed terminal stops both thread their latest startId into stopSelfResult. */
    @Test
    fun stopUsesStartIdResultAcrossEarlyAndObservedTeardown() {
        TtsPlaybackForegroundService.hostResolver = { null }
        val missingHostController = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val missingHostService = missingHostController.get()
        val context = RuntimeEnvironment.getApplication()
        missingHostService.onStartCommand(Intent(context, missingHostService::class.java), 0, 4)
        val missingShadow = shadowOf(missingHostService as Service) as QueuedStartShadowService
        assertEquals(1, missingShadow.publishedNotifications.size)
        assertEquals(listOf("startForeground:0", "stopSelfResult:4:true", "stopForeground:true"), missingShadow.events)
        assertNull(missingShadow.lastForegroundNotification)
        assertTrue(missingShadow.isForegroundStopped)
        assertTrue(missingShadow.notificationShouldRemoved)
        assertTrue(missingShadow.isStoppedBySelf)
        assertEquals(4, missingShadow.stopSelfResultId)
        missingHostController.destroy()

        val harness = TtsPlaybackForegroundServiceTest.ServiceHarness()
        TtsPlaybackForegroundService.hostResolver = { harness.host }
        harness.speak()
        val activeController = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val activeService = activeController.get()
        activeService.onStartCommand(Intent(context, activeService::class.java), 0, 1)
        activeService.onStartCommand(
            Intent(context, activeService::class.java).setAction(TtsPlaybackForegroundService.ACTION_PAUSE),
            0,
            2,
        )
        activeService.onStartCommand(
            Intent(context, activeService::class.java).setAction(TtsPlaybackForegroundService.ACTION_STOP),
            0,
            6,
        )
        shadowOf(Looper.getMainLooper()).idle()

        val activeShadow = shadowOf(activeService as Service) as QueuedStartShadowService
        assertEquals(listOf(6), activeShadow.stopSelfResultCalls)
        assertNull(activeShadow.lastForegroundNotification)
        assertTrue(activeShadow.isForegroundStopped)
        assertTrue(activeShadow.notificationShouldRemoved)
        assertTrue(activeShadow.isStoppedBySelf)
        assertEquals(6, activeShadow.stopSelfResultId)
        activeController.destroy()
    }

    /** An idle early start keeps foreground state when stopSelfResult rejects stopping for a queued start. */
    @Test
    fun earlyStopKeepsForegroundWhenReplacementStartIsQueued() {
        val harness = TtsPlaybackForegroundServiceTest.ServiceHarness()
        TtsPlaybackForegroundService.hostResolver = { harness.host }
        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        shadow.rejectedStopSelfIds += 1

        service.onStartCommand(Intent(context, service::class.java), 0, 1)

        assertEquals(1, shadow.publishedNotifications.size)
        assertEquals(listOf("startForeground:0", "stopSelfResult:1:false"), shadow.events)
        assertTrue(shadow.stopForegroundCalls.isEmpty())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertNotNull(shadow.lastForegroundNotification)

        harness.speak("Replacement private sentence.")
        service.onStartCommand(Intent(context, service::class.java), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertNotNull(shadow.lastForegroundNotification)

        harness.controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1, 2), shadow.stopSelfResultCalls)
        assertEquals(listOf(true), shadow.stopForegroundCalls)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(2, shadow.stopSelfResultId)
        assertNull(shadow.lastForegroundNotification)
        controller.destroy()
    }

    /** An observed terminal stop keeps foreground state when a newer TTS start is already queued. */
    @Test
    fun observedTerminalStopKeepsForegroundWhenReplacementStartIsQueued() {
        val harness = TtsPlaybackForegroundServiceTest.ServiceHarness()
        TtsPlaybackForegroundService.hostResolver = { harness.host }
        harness.speak("First sentence.")
        val controller = Robolectric.buildService(TtsPlaybackForegroundService::class.java).create()
        val service = controller.get()
        val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        service.onStartCommand(Intent(context, service::class.java), 0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        shadow.rejectedStopSelfIds += 1
        harness.controller.stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(1), shadow.stopSelfResultCalls)
        assertTrue(shadow.stopForegroundCalls.isEmpty())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertNotNull(shadow.lastForegroundNotification)

        harness.speak("Replacement sentence.")
        service.onStartCommand(Intent(context, service::class.java), 0, 2)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)

        harness.controller.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(1, 2), shadow.stopSelfResultCalls)
        assertEquals(listOf(true), shadow.stopForegroundCalls)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(2, shadow.stopSelfResultId)
        assertNull(shadow.lastForegroundNotification)
        controller.destroy()
    }

    private companion object {
        // Captured before any test overrides it, so teardown restores the production resolver.
        val defaultResolver = TtsPlaybackForegroundService.hostResolver
    }
}
