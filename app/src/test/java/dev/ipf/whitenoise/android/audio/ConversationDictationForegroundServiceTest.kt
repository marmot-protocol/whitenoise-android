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
import android.widget.Button
import android.widget.FrameLayout
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionNotification
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
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
import org.robolectric.shadows.ShadowLog

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
                        null
                    } else {
                        draft = value
                        revision += 1
                        revision
                    }
                },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                targetValidationScope = scope,
                startDurableSession = { _, ready ->
                    if (autoReady) ready()
                    true
                },
                stopDurableSession = {
                    ConversationDictationForegroundService.stop(RuntimeEnvironment.getApplication())
                },
                silenceDeliveryMode = { preference },
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

    /** A stale PendingIntent in a new process cannot initialize MDK or a speech controller. */
    @Test
    @Config(application = WhiteNoiseApplication::class)
    fun unownedCompletionCommandKeepsApplicationStateLazy() {
        ConversationDictationForegroundService.hostResolver = defaultResolver
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        assertNull(application.initializedAppState())
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(
            Intent(service, service::class.java)
                .setAction(ConversationDictationForegroundService.ACTION_SEND)
                .putExtra(ConversationDictationForegroundService.EXTRA_SESSION_TOKEN, "old-process:1"),
            0,
            1,
        )
        assertNull(application.initializedAppState())
        lifecycle.destroy()
        assertNull(application.initializedAppState())
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

    @Test
    fun serviceTraceCorrelatesTheRequestedSession() {
        ShadowLog.clear()
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        try {
            val service = lifecycle.get()
            service.onStartCommand(startIntent(service, harness), 0, 1)
            assertCorrelatedServiceTrace()
        } finally {
            lifecycle.destroy()
        }
    }

    private fun assertCorrelatedServiceTrace() {
        val trace = ShadowLog.getLogsForTag("WNDictation").mapNotNull { DictationDiagnosticSchema.fields(it.msg) }
        assertTrue(trace.any { it["event"] == "foreground_service_on_start" && it["callback_session"] == 1L })
        assertTrue(trace.any { it["event"] == "foreground_service_promoted" && it["callback_session"] == 1L })
    }

    /** Verifies active capture uses a metadata-free notification whose actions reach the controller. */
    @Test
    fun activeSessionUsesGenericForegroundNotificationAndRoutesActions() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
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
        assertTrue(notification.actions.isNullOrEmpty())
        assertNotNull(notification.contentView)
        assertNull(notification.bigContentView)
        assertCompactButtonsEnabled(service, notification)
        assertEquals("Starting dictation…", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertExplicitNotificationDestinations(service, notification)

        service.onStartCommand(
            actionCommand(service, harness, ConversationDictationForegroundService.ACTION_PASTE),
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
        assertFalse(processing.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertTrue(processing.actions.isNullOrEmpty())
        assertNull(processing.bigContentView)
        assertCompactCompletionButtonsDisabled(service, processing)

        service.onStartCommand(
            actionCommand(service, harness, ConversationDictationForegroundService.ACTION_CANCEL),
            0,
            3,
        )
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        serviceController.destroy()

        val sendHarness = installHost()
        val sendServiceController =
            Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val sendService = sendServiceController.get()
        sendService.onStartCommand(startIntent(sendService, sendHarness), 0, 1)
        sendService.onStartCommand(
            actionCommand(sendService, sendHarness, ConversationDictationForegroundService.ACTION_SEND),
            0,
            2,
        )
        assertTrue(sendHarness.conversationDictation.state is ConversationDictationState.Processing)
        sendServiceController.destroy()
    }

    /** Connection refreshes reuse the same foreground ID and cannot hide live dictation commands. */
    @Test
    fun backgroundRefreshPreservesTheSingleDictationControlNotification() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)

        val backgroundRefresh = service.foreground.foregroundNotification()
        assertEquals("Dictation active", backgroundRefresh.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(backgroundRefresh.actions.isNullOrEmpty())
        assertNull(backgroundRefresh.bigContentView)
        assertCompactButtonsEnabled(service, backgroundRefresh)
        lifecycle.destroy()
    }

    /** Ending dictation restores the normal connection display if that foreground service remains active. */
    @Test
    fun endingDictationRestoresBackgroundConnectionPresentation() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)

        harness.conversationDictation.cancel()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val restored =
            service
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single { it.id == BackgroundConnectionNotification.NOTIFICATION_ID }
                .notification
        assertEquals(
            "White Noise is connected",
            restored.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        lifecycle.destroy()
    }

    /** Once dictation ends without a connection owner, its shared notification ID is removed. */
    @Test
    fun endingDictationWithoutBackgroundConnectionRemovesNotification() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        Snapshot.sendApplyNotifications()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            service.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == BackgroundConnectionNotification.NOTIFICATION_ID
            },
        )

        lifecycle.destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(
            service.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == BackgroundConnectionNotification.NOTIFICATION_ID
            },
        )
    }

    /** A completed result removes controls without waiting for a service-destruction callback. */
    @Test
    fun pasteCompletionRestoresConnectionBeforeServiceDestruction() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)

        harness.conversationDictation.paste()
        harness.platform.listener.onResult("completed transcript")

        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertNull(ConversationDictationForegroundService.activeNotificationOrNull())
        val restored =
            service
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single()
                .notification
        assertEquals("White Noise is connected", restored.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        lifecycle.destroy()
    }

    /** A Cancel delivered as the first command never queues a foreground notification. */
    @Test
    fun cancelDuringFirstStartNeverPromotesNotification() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()

        service.onStartCommand(
            actionCommand(service, harness, ConversationDictationForegroundService.ACTION_CANCEL),
            0,
            1,
        )

        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertNull(shadowOf(service as Service).lastForegroundNotification)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        lifecycle.destroy()
    }

    private fun assertExplicitNotificationDestinations(
        service: NotificationStreamForegroundService,
        notification: Notification,
    ) {
        assertEquals(
            ComponentName(service, MainActivity::class.java),
            shadowOf(notification.contentIntent).savedIntent.component,
        )
        notificationActions.forEach { action ->
            assertEquals(
                ComponentName(service, NotificationStreamForegroundService::class.java),
                shadowOf(service.foreground.dictation.actionIntent(action, "test-token")).savedIntent.component,
            )
        }
    }

    /** Inflates the actual collapsed RemoteViews, including its enable-state actions. */
    private fun assertCompactButtonsEnabled(
        service: NotificationStreamForegroundService,
        notification: Notification,
    ) {
        val compact = notification.contentView.apply(service, FrameLayout(service))
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_cancel).isEnabled)
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_paste).isEnabled)
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_send).isEnabled)
    }

    private fun assertCompactCompletionButtonsDisabled(
        service: NotificationStreamForegroundService,
        notification: Notification,
    ) {
        val compact = notification.contentView.apply(service, FrameLayout(service))
        assertTrue(compact.findViewById<Button>(R.id.dictation_notification_cancel).isEnabled)
        assertFalse(compact.findViewById<Button>(R.id.dictation_notification_paste).isEnabled)
        assertFalse(compact.findViewById<Button>(R.id.dictation_notification_send).isEnabled)
    }

    private fun actionCommand(
        service: NotificationStreamForegroundService,
        harness: Harness,
        action: String,
    ): Intent =
        shadowOf(
            service.foreground.dictation.actionIntent(
                action,
                requireNotNull(harness.conversationDictation.notificationSessionToken),
            ),
        ).savedIntent

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
                        Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
                    val service = lifecycle.get()
                    service.onStartCommand(startIntent(service, harness), 0, 1)
                    val action =
                        actionCommand(
                            service,
                            harness,
                            notificationActions[actionIndex],
                        )
                    val listener = harness.platform.listener

                    service.onStartCommand(action, 0, 2)
                    service.onStartCommand(action, 0, 3)
                    listener.onResult("notification transcript")
                    runCurrent()
                    listener.onResult("stale duplicate")

                    assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                    assertTrue(shadowOf(service as Service).isForegroundStopped)
                    assertNull(ConversationDictationForegroundService.activeNotificationOrNull())
                    assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
                    Snapshot.sendApplyNotifications()
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
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
        val serviceController = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = serviceController.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val oldToken = requireNotNull(harness.conversationDictation.notificationSessionToken)
        val oldActions = notificationActions.map { service.foreground.dictation.actionIntent(it, oldToken) }
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        service.onStartCommand(startIntent(service, harness), 0, 2)
        val replacement = harness.conversationDictation.state
        val newToken = requireNotNull(harness.conversationDictation.notificationSessionToken)

        oldActions.forEachIndexed { index, action ->
            service.onStartCommand(shadowOf(action).savedIntent, 0, 3 + index)
            assertEquals(replacement, harness.conversationDictation.state)
            assertFalse(action == service.foreground.dictation.actionIntent(notificationActions[index], newToken))
        }
        service.onStartCommand(
            Intent(service, service::class.java).setAction(ConversationDictationForegroundService.ACTION_SEND),
            0,
            6,
        )
        assertEquals(replacement, harness.conversationDictation.state)

        // Even a controller recreated in the same process has a distinct token when its counter restarts.
        val recreated = installHost()
        service.onStartCommand(shadowOf(oldActions[0]).savedIntent, 0, 7)
        assertTrue(recreated.conversationDictation.state is ConversationDictationState.Starting)
        serviceController.destroy()
    }

    /** A terminal retained-audio failure removes microphone controls without discarding Retry data. */
    @Test
    fun retainedAudioFailureReleasesNotificationWithAndWithoutConnection() {
        listOf(false, true).forEach { connected ->
            listOf(
                ConversationDictationForegroundService.ACTION_PASTE,
                ConversationDictationForegroundService.ACTION_SEND,
            ).forEach { action ->
                val harness = installHost()
                val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
                val service = lifecycle.get()
                service.onStartCommand(startIntent(service, harness), 0, 1)
                if (connected) service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
                harness.platform.pendingCallerAudio = true
                service.onStartCommand(actionCommand(service, harness, action), 0, 2)
                harness.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)

                assertTrue(harness.conversationDictation.state is ConversationDictationState.Failed)
                assertFalse(harness.conversationDictation.hasDurableSession)
                assertNull(ConversationDictationForegroundService.activeNotificationOrNull())
                assertFalse(service.foreground.dictation.hasForegroundLease)
                val notifications = service.getSystemService(NotificationManager::class.java).activeNotifications
                if (connected) {
                    assertEquals(1, notifications.size)
                    assertEquals(
                        BackgroundConnectionNotification.CHANNEL_ID,
                        notifications.single().notification.channelId,
                    )
                } else {
                    assertTrue(notifications.isEmpty())
                    assertTrue(shadowOf(service as Service).isForegroundStopped)
                }
                assertTrue(harness.platform.pendingCallerAudio)
                lifecycle.destroy()
                assertTrue(harness.platform.pendingCallerAudio)
                harness.conversationDictation.dismissFailure()
            }
        }
    }

    /** Verifies recents removal preserves explicit capture but service destruction fails it closed. */
    @Test
    fun recentsSwipeKeepsCaptureButServiceDestructionCancelsIt() {
        val harness = installHost()
        val serviceController = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
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
        val serviceController = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
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
                    .buildService(NotificationStreamForegroundService::class.java)
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
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
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
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
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
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(
            Intent(service, service::class.java).setAction(ConversationDictationForegroundService.ACTION_START),
            0,
            1,
        )
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
        val oldLifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val oldService = oldLifecycle.get()
        oldService.onStartCommand(startIntent(oldService, harness), 0, 1)
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        val newLifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val newService = newLifecycle.get()
        newService.onStartCommand(startIntent(newService, harness), 0, 1)
        oldLifecycle.destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertFalse(harness.conversationDictation.ownsMicrophone)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500L))
        assertTrue(harness.conversationDictation.ownsMicrophone)
        val replacementNotification =
            newService
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single { it.id == BackgroundConnectionNotification.NOTIFICATION_ID }
                .notification
        assertEquals(
            "Dictation active",
            replacementNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertCompactButtonsEnabled(newService, replacementNotification)
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
        var pendingCallerAudio = false

        lateinit var listener: ConversationDictationRecognitionListener

        /** Test sessions always begin with record-audio permission. */
        override fun hasRecordAudioPermission(): Boolean = true

        /** Test sessions always expose an in-process recognizer. */
        override fun recognitionAvailable(): Boolean = true

        override fun callerAudioHasPending(): Boolean = pendingCallerAudio

        override fun finishCallerAudioCapture(onClosed: () -> Unit): Boolean {
            if (!pendingCallerAudio) return false
            onClosed()
            return true
        }

        override fun discardCallerAudio(onClosed: () -> Unit): Boolean {
            if (!pendingCallerAudio) return false
            pendingCallerAudio = false
            onClosed()
            return true
        }

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
        val notificationActions =
            listOf(
                ConversationDictationForegroundService.ACTION_CANCEL,
                ConversationDictationForegroundService.ACTION_PASTE,
                ConversationDictationForegroundService.ACTION_SEND,
            )
        val defaultResolver = ConversationDictationForegroundService.hostResolver
        val defaultForegroundPromoter = ConversationDictationForegroundService.foregroundPromoter
    }
}
