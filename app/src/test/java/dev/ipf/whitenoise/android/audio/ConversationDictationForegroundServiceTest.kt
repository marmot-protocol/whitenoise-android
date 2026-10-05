package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionNotification
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
internal class ConversationDictationForegroundServiceTest : ConversationDictationForegroundTestCase() {
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
                it.id == NotificationStreamForegroundService.DICTATION_NOTIFICATION_ID
            },
        )

        lifecycle.destroy()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(
            service.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == NotificationStreamForegroundService.DICTATION_NOTIFICATION_ID
            },
        )
    }

    /** Keep connected off must retire completed controls without relying on asynchronous destruction. */
    @Test
    fun pasteCompletionWithoutConnectionRemovesControlsImmediately() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        harness.conversationDictation.paste()
        harness.platform.listener.onResult("completed transcript")
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertTrue(shadowOf(service as Service).isForegroundStopped)
        assertNull(ConversationDictationForegroundService.activeNotificationOrNull())
        assertFalse(service.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.notification.channelId == ConversationDictationForegroundService.CHANNEL_ID
        })
        lifecycle.destroy()
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

    /** Real notification intents produce the selected outcome regardless of the stored default. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun notificationActionsDeliverOnceAndOverrideBothStoredPreferences() =
        runTest {
            ConversationDictationDeliveryMode.entries.forEach { preference ->
                (0..2).forEach { actionIndex ->
                    val harness = DictationForegroundTestHost(this, preference)
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

    /** Hiding the drawer card is presentation only: explicit in-app completion still owns the session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @Config(sdk = [34, 35])
    fun dismissedNotificationDoesNotCancelCaptureOrChangeSendToPaste() =
        runTest {
            listOf(false, true).forEach { send ->
                val harness = DictationForegroundTestHost(scope = this)
                ConversationDictationForegroundService.hostResolver = { harness }
                val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
                val service = lifecycle.get()
                service.onStartCommand(startIntent(service, harness), 0, 1)
                val manager = service.getSystemService(NotificationManager::class.java)
                val notification = manager.activeNotifications.single().notification
                // Android invokes no cancellation callback when the user hides an ongoing card.
                assertNull(notification.deleteIntent)
                manager.cancel(BackgroundConnectionNotification.NOTIFICATION_ID)
                assertTrue(harness.conversationDictation.hasDurableSession)
                assertTrue(harness.conversationDictation.ownsMicrophone)
                if (send) harness.conversationDictation.send() else harness.conversationDictation.paste()
                harness.platform.listener.onResult("after notification dismissal")
                runCurrent()
                assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                assertEquals(if (send) listOf("after notification dismissal") else emptyList<String>(), harness.sent)
                assertEquals(if (send) "" else "after notification dismissal", harness.draft.text)
                assertTrue(manager.activeNotifications.isEmpty())
                lifecycle.destroy()
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
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ConversationDictationForegroundServiceStartTest : ConversationDictationForegroundTestCase() {
    /** A terminal retained-audio failure removes microphone controls without discarding Retry data. */
    @Test
    fun retainedAudioFailureReplacesControlsWithBoundedRecoveryWithAndWithoutConnection() {
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
                Snapshot.sendApplyNotifications()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(harness.conversationDictation.hasDurableSession)
                assertFalse(harness.conversationDictation.foregroundMicrophoneRequired)
                assertTrue(service.foreground.dictation.hasForegroundLease)
                val notification =
                    service
                        .getSystemService(NotificationManager::class.java)
                        .activeNotifications
                        .single()
                        .notification
                assertEquals(
                    "Dictation needs attention",
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
                )
                assertNull(notification.contentView)
                assertEquals(1, notification.actions.size)
                assertEquals(
                    "Open app",
                    notification.actions
                        .single()
                        .title
                        .toString(),
                )
                service.onStartCommand(actionCommand(service, harness, action), 0, 3)
                service.onStartCommand(
                    actionCommand(service, harness, ConversationDictationForegroundService.ACTION_CANCEL),
                    0,
                    4,
                )
                assertTrue(harness.conversationDictation.state is ConversationDictationState.Failed)
                assertTrue(harness.platform.pendingCallerAudio)
                lifecycle.destroy()
                assertTrue(harness.platform.pendingCallerAudio)
                harness.conversationDictation.dismissFailure()
            }
        }
    }

    /** A stale cancel/send/paste from before a failure cannot act on the same session after Retry. */
    @Test
    fun notificationActionGenerationFencesFailureAndRetainedRetry() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val old = notificationActions.map { actionCommand(service, harness, it) }
        harness.platform.pendingCallerAudio = true
        harness.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        old.forEach { service.onStartCommand(it, 0, 2) }
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Failed)
        harness.conversationDictation.retry()
        val retryState = harness.conversationDictation.state
        old.forEach { service.onStartCommand(it, 0, 3) }
        assertEquals(retryState, harness.conversationDictation.state)
        assertTrue(harness.platform.pendingCallerAudio)
        assertEquals("", harness.draft.text)
        assertTrue(harness.sent.isEmpty())
        harness.conversationDictation.cancel()
        lifecycle.destroy()
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
            val harness = DictationForegroundTestHost(autoReady = false)
            ConversationDictationForegroundService.hostResolver = { harness }
            ConversationDictationForegroundService.foregroundPromoter = { _, _, _ -> throw failure }
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
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        ConversationDictationForegroundService.foregroundPromoter = { _, _, _ ->
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
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        ConversationDictationForegroundService.foregroundPromoter = { service, notification, type ->
            assertEquals(0, harness.platform.sessionsCreated)
            assertFalse(harness.conversationDictation.ownsMicrophone)
            defaultForegroundPromoter(service, notification, type)
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
        val harness = DictationForegroundTestHost(autoReady = false)
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
                .single { it.id == NotificationStreamForegroundService.DICTATION_NOTIFICATION_ID }
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
}
