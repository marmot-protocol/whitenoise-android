package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.text.input.TextFieldValue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationDictationForegroundServiceTest {
    private class RejectingForegroundStartContext(
        base: Context,
    ) : ContextWrapper(base) {
        /** Simulates Android rejecting a foreground-service launch before service creation. */
        override fun startForegroundService(service: Intent): ComponentName? = throw IllegalStateException("blocked")
    }

    private class Harness : ConversationDictationServiceHost {
        private val platform = FakePlatform()
        override val conversationDictation =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(""), 0L) },
                writeDraft = { _, _, _, _ -> true },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
            )

        init {
            conversationDictation.requestStart("account", "group", TextFieldValue(""))
        }

        /** Terminates controller ownership before a queued service start is delivered. */
        fun failRecognition() {
            platform.listener.onError(ConversationDictationFailure.Network)
        }
    }

    /** Restores process-wide service seams so each Robolectric case starts isolated. */
    @After
    fun restoreResolver() {
        ConversationDictationForegroundService.hostResolver = defaultResolver
        ConversationDictationForegroundService.foregroundPromoter = defaultForegroundPromoter
    }

    /** Verifies active capture uses a metadata-free notification whose actions reach the controller. */
    @Test
    fun activeSessionUsesGenericForegroundNotificationAndRoutesActions() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = serviceController.get()

        service.onStartCommand(Intent(service, service::class.java), 0, 1)

        val notification = shadowOf(service as Service).lastForegroundNotification
        assertNotNull(notification)
        val title =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
        assertFalse(title.contains("account", ignoreCase = true))
        assertFalse(title.contains("group", ignoreCase = true))

        service.onStartCommand(
            Intent(service, service::class.java).setAction(ConversationDictationForegroundService.ACTION_DONE),
            0,
            2,
        )
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Processing)

        service.onStartCommand(
            Intent(service, service::class.java).setAction(ConversationDictationForegroundService.ACTION_CANCEL),
            0,
            3,
        )
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        serviceController.destroy()
    }

    /** Verifies recents removal preserves explicit capture but service destruction fails it closed. */
    @Test
    fun recentsSwipeKeepsCaptureButServiceDestructionCancelsIt() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(Intent(service, service::class.java), 0, 1)

        service.onTaskRemoved(null)
        assertTrue(harness.conversationDictation.hasPendingSession)

        serviceController.destroy()
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
    }

    /** Verifies synchronous foreground-service launch rejection is reported without throwing. */
    @Test
    fun rejectedForegroundStartFailsClosed() {
        val context = RejectingForegroundStartContext(RuntimeEnvironment.getApplication())

        assertFalse(ConversationDictationForegroundService.start(context))
    }

    /** Verifies a stale queued start cannot promote an orphan service after controller failure. */
    @Test
    fun queuedStartAfterControllerFailureDoesNotPromoteAnOrphanService() {
        val harness = installHost()
        harness.failRecognition()
        assertTrue(harness.conversationDictation.hasPendingSession)
        assertFalse(harness.conversationDictation.hasDurableSession)
        val serviceController = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = serviceController.get()

        val result = service.onStartCommand(Intent(service, service::class.java), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertNull(shadowOf(service as Service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        serviceController.destroy()
    }

    /** Verifies platform foreground-promotion rejection cancels capture and stops the service. */
    @Test
    fun foregroundPromotionRejectionCancelsCaptureAndStopsTheService() {
        listOf<RuntimeException>(
            SecurityException("blocked"),
            ForegroundServiceStartNotAllowedException("blocked"),
        ).forEach { failure ->
            val harness = installHost()
            ConversationDictationForegroundService.foregroundPromoter = { _, _ -> throw failure }
            val serviceController =
                Robolectric
                    .buildService(ConversationDictationForegroundService::class.java)
                    .create()
            val service = serviceController.get()

            val result = service.onStartCommand(Intent(service, service::class.java), 0, 1)

            assertEquals(Service.START_NOT_STICKY, result)
            assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
            assertFalse(harness.conversationDictation.hasDurableSession)
            assertTrue(shadowOf(service as Service).isStoppedBySelf)
            serviceController.destroy()
        }
    }

    /** Installs a fresh process-owner harness into the service resolver seam. */
    private fun installHost(): Harness =
        Harness().also { installed ->
            ConversationDictationForegroundService.hostResolver = { installed }
        }

    private class FakePlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        /** Test sessions always begin with record-audio permission. */
        override fun hasRecordAudioPermission(): Boolean = true

        /** Test sessions always expose an in-process recognizer. */
        override fun recognitionAvailable(): Boolean = true

        /** Captures the listener and returns a no-op provider generation. */
        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }

    private companion object {
        val defaultResolver = ConversationDictationForegroundService.hostResolver
        val defaultForegroundPromoter = ConversationDictationForegroundService.foregroundPromoter
    }
}
