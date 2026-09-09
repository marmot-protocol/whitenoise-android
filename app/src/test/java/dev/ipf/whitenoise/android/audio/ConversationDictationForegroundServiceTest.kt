package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    private class Harness(
        scope: CoroutineScope? = null,
        preference: ConversationDictationDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
        autoReady: Boolean = true,
    ) : ConversationDictationServiceHost {
        val platform = FakePlatform()
        var draft = TextFieldValue("")
        var revision = 0L
        val sent = mutableListOf<String>()
        override val conversationDictation =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, revision) },
                writeDraft = { _, _, expected, value ->
                    if (expected != revision) {
                        false
                    } else {
                        draft = value
                        revision += 1
                        true
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                targetValidationScope = scope,
                startDurableSession = { _, ready ->
                    if (autoReady) ready()
                    true
                },
                deliveryMode = { preference },
                sendTranscriptIfOriginUnchanged = { request ->
                    request.beginDispatch().also { if (it) sent += request.payload }
                },
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

    /** App-wide denial and a disabled dictation channel both hide drawer controls. */
    @Test
    fun notificationAvailabilityHonorsAppAndChannelSettings() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setNotificationsEnabled(true)
        assertTrue(ConversationDictationForegroundService.notificationControlsAvailable(context))
        shadow.setNotificationsEnabled(false)
        assertFalse(ConversationDictationForegroundService.notificationControlsAvailable(context))
        shadow.setNotificationsEnabled(true)
        manager.createNotificationChannel(
            NotificationChannel(
                ConversationDictationForegroundService.CHANNEL_ID,
                "Dictation",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertFalse(ConversationDictationForegroundService.notificationControlsAvailable(context))
    }

    /** Verifies active capture uses a metadata-free notification whose actions reach the controller. */
    @Test
    fun activeSessionUsesGenericForegroundNotificationAndRoutesActions() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = serviceController.get()

        service.onStartCommand(startIntent(service, harness), 0, 1)

        val notification = shadowOf(service as Service).lastForegroundNotification
        assertNotNull(notification)
        val title =
            notification.extras
                .getCharSequence(android.app.Notification.EXTRA_TITLE)
                ?.toString()
                .orEmpty()
        assertFalse(title.contains("account", ignoreCase = true))
        assertFalse(title.contains("group", ignoreCase = true))
        assertEquals(listOf("Cancel", "Paste", "Send"), notification.actions.map { it.title.toString() })
        assertEquals("Starting dictation…", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertExplicitNotificationDestinations(service, notification)

        service.onStartCommand(
            shadowOf(notification.actions[1].actionIntent).savedIntent,
            0,
            2,
        )
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Processing)
        Snapshot.sendApplyNotifications()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val processing =
            service
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single()
                .notification
        assertEquals("Transcribing…", processing.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(processing.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertNotNull(processing.actions[0].actionIntent)
        assertNull(processing.actions[1].actionIntent)
        assertNull(processing.actions[2].actionIntent)

        service.onStartCommand(
            shadowOf(notification.actions[0].actionIntent).savedIntent,
            0,
            3,
        )
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        serviceController.destroy()

        val sendHarness = installHost()
        val sendServiceController =
            Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val sendService = sendServiceController.get()
        sendService.onStartCommand(startIntent(sendService, sendHarness), 0, 1)
        sendService.onStartCommand(
            shadowOf(shadowOf(sendService as Service).lastForegroundNotification.actions[2].actionIntent).savedIntent,
            0,
            2,
        )
        assertTrue(sendHarness.conversationDictation.state is ConversationDictationState.Processing)
        sendServiceController.destroy()
    }

    private fun assertExplicitNotificationDestinations(
        service: ConversationDictationForegroundService,
        notification: Notification,
    ) {
        assertEquals(
            ComponentName(service, MainActivity::class.java),
            shadowOf(notification.contentIntent).savedIntent.component,
        )
        notification.actions.forEach { action ->
            assertEquals(
                ComponentName(service, ConversationDictationForegroundService::class.java),
                shadowOf(action.actionIntent).savedIntent.component,
            )
        }
    }

    /** Real notification intents produce the selected outcome regardless of the stored default. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationActionsDeliverOnceAndOverrideBothStoredPreferences() =
        runTest {
            ConversationDictationDeliveryMode.entries.forEach { preference ->
                (0..2).forEach { actionIndex ->
                    val harness = Harness(this, preference)
                    ConversationDictationForegroundService.hostResolver = { harness }
                    val lifecycle =
                        Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
                    val service = lifecycle.get()
                    service.onStartCommand(startIntent(service, harness), 0, 1)
                    val notification = shadowOf(service as Service).lastForegroundNotification
                    val action = shadowOf(notification.actions[actionIndex].actionIntent).savedIntent
                    val listener = harness.platform.listener

                    service.onStartCommand(action, 0, 2)
                    service.onStartCommand(action, 0, 3)
                    listener.onResult("notification transcript")
                    runCurrent()
                    listener.onResult("stale duplicate")

                    assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                    assertEquals(if (actionIndex == 1) "notification transcript" else "", harness.draft.text)
                    assertEquals(
                        if (actionIndex == 2) listOf("notification transcript") else emptyList<String>(),
                        harness.sent,
                    )
                    lifecycle.destroy()
                }
            }
        }

    /** Delayed notification taps cannot send, paste, or cancel a replacement session. */
    @Test
    fun oldAndUnboundNotificationActionsCannotAffectAnotherSession() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val oldNotification = shadowOf(service as Service).lastForegroundNotification
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        service.onStartCommand(startIntent(service, harness), 0, 2)
        val replacement = harness.conversationDictation.state
        val newNotification = shadowOf(service as Service).lastForegroundNotification

        oldNotification.actions.forEachIndexed { index, action ->
            service.onStartCommand(shadowOf(action.actionIntent).savedIntent, 0, 3 + index)
            assertEquals(replacement, harness.conversationDictation.state)
            assertFalse(action.actionIntent == newNotification.actions[index].actionIntent)
        }
        service.onStartCommand(
            Intent(service, service::class.java).setAction(ConversationDictationForegroundService.ACTION_SEND),
            0,
            6,
        )
        assertEquals(replacement, harness.conversationDictation.state)

        // Even a controller recreated in the same process has a distinct token when its counter restarts.
        val recreated = installHost()
        service.onStartCommand(shadowOf(oldNotification.actions[0].actionIntent).savedIntent, 0, 7)
        assertTrue(recreated.conversationDictation.state is ConversationDictationState.Starting)
        serviceController.destroy()
    }

    /** Verifies recents removal preserves explicit capture but service destruction fails it closed. */
    @Test
    fun recentsSwipeKeepsCaptureButServiceDestructionCancelsIt() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)

        service.onTaskRemoved(null)
        assertTrue(harness.conversationDictation.hasPendingSession)

        serviceController.destroy()
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
    }

    /** Verifies synchronous foreground-service launch rejection is reported without throwing. */
    @Test
    fun rejectedForegroundStartFailsClosed() {
        val context = RejectingForegroundStartContext(RuntimeEnvironment.getApplication())

        assertFalse(ConversationDictationForegroundService.start(context, "test-token"))
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

        val result = service.onStartCommand(startIntent(service, harness), 0, 1)

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

            val result = service.onStartCommand(startIntent(service, harness), 0, 1)

            assertEquals(Service.START_NOT_STICKY, result)
            assertTrue(harness.conversationDictation.state is ConversationDictationState.Failed)
            assertFalse(harness.conversationDictation.hasDurableSession)
            assertTrue(shadowOf(service as Service).isStoppedBySelf)
            serviceController.destroy()
        }
    }

    @Test
    fun ownershipLostDuringPromotionCannotBePublishedOrStartCapture() {
        val harness = Harness(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        ConversationDictationForegroundService.foregroundPromoter = { _, _ ->
            harness.conversationDictation.cancel()
        }
        val lifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = lifecycle.get()

        service.onStartCommand(startIntent(service, harness), 0, 1)

        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertEquals(0, harness.platform.sessionsCreated)
        assertTrue(shadowOf(service as Service).isStoppedBySelf)
        lifecycle.destroy()
    }

    /** Native capture begins strictly after foreground promotion returns, never on enqueue alone. */
    @Test
    fun deferredCaptureStartsOnlyAfterSuccessfulPromotion() {
        val harness = Harness(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        ConversationDictationForegroundService.foregroundPromoter = { service, notification ->
            assertEquals(0, harness.platform.sessionsCreated)
            assertFalse(harness.conversationDictation.ownsMicrophone)
            defaultForegroundPromoter(service, notification)
        }
        val lifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        assertEquals(1, harness.platform.sessionsCreated)
        assertTrue(harness.conversationDictation.ownsMicrophone)
        lifecycle.destroy()
    }

    /** An unbound queued start cannot acknowledge the current session or leave an orphan FGS. */
    @Test
    fun unboundQueuedStartStopsWithoutOpeningMicrophone() {
        val harness = Harness(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val lifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(Intent(service, service::class.java), 0, 1)
        assertEquals(0, harness.platform.sessionsCreated)
        assertNull(shadowOf(service as Service).lastForegroundNotification)
        assertTrue(shadowOf(service).isStoppedBySelf)
        lifecycle.destroy()
        assertTrue(harness.conversationDictation.hasDurableSession)
        harness.conversationDictation.cancel()
    }

    /** Destruction is tied to the controller and token actually promoted by that service instance. */
    @Test
    fun staleServiceDestructionCannotCancelReplacement() {
        val harness = installHost()
        val oldLifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val oldService = oldLifecycle.get()
        oldService.onStartCommand(startIntent(oldService, harness), 0, 1)
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        val newLifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val newService = newLifecycle.get()
        newService.onStartCommand(startIntent(newService, harness), 0, 1)
        oldLifecycle.destroy()
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertTrue(harness.conversationDictation.ownsMicrophone)
        newLifecycle.destroy()
        assertFalse(harness.conversationDictation.hasDurableSession)
    }

    /** The enqueue intent carries identity before Android creates the service. */
    @Test
    fun foregroundStartCarriesSessionToken() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(ConversationDictationForegroundService.start(context, "current-session"))
        assertEquals(
            "current-session",
            shadowOf(context)
                .nextStartedService
                .getStringExtra(ConversationDictationForegroundService.EXTRA_SESSION_TOKEN),
        )
    }

    /** Installs a fresh process-owner harness into the service resolver seam. */
    private fun installHost(): Harness =
        Harness().also { installed ->
            ConversationDictationForegroundService.hostResolver = { installed }
        }

    private fun startIntent(
        service: Service,
        harness: Harness,
    ): Intent =
        Intent(service, service::class.java)
            .putExtra(
                ConversationDictationForegroundService.EXTRA_SESSION_TOKEN,
                requireNotNull(harness.conversationDictation.notificationSessionToken),
            )

    private class FakePlatform : ConversationDictationPlatform {
        var sessionsCreated = 0

        lateinit var listener: ConversationDictationRecognitionListener

        /** Test sessions always begin with record-audio permission. */
        override fun hasRecordAudioPermission(): Boolean = true

        /** Test sessions always expose an in-process recognizer. */
        override fun recognitionAvailable(): Boolean = true

        /** Captures the listener and returns a no-op provider generation. */
        @Suppress("MaxLineLength")
        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
            this.listener = listener
            sessionsCreated++
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
