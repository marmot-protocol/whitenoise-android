package dev.ipf.whitenoise.android.audio

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionNotification
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ConversationDictationNotificationRestorationTest : ConversationDictationNotificationTestFixture() {
    /** Expiry as the first channel user must preserve the foreground card's badge-free settings. */
    @Test
    fun expiryCreatesTheSharedBadgeFreeDictationChannel() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(ConversationDictationForegroundService.CHANNEL_ID)
        notifyConversationDictationRecoveryExpired(context)
        val channel = requireNotNull(manager.getNotificationChannel(ConversationDictationForegroundService.CHANNEL_ID))
        assertFalse(channel.canShowBadge())
        assertEquals(context.getString(R.string.notification_channel_dictation_description), channel.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        val notice = shadowOf(manager).allNotifications.single()
        assertEquals(
            context.getString(R.string.dictation_recovery_expired_text),
            notice.extras.getString(Notification.EXTRA_TEXT),
        )
        assertFalse(
            notice
                .extras
                .getString(Notification.EXTRA_TEXT)
                .orEmpty()
                .contains("cleared"),
        )
        assertTrue(notice.contentIntent != null)
    }

    /** Stopping connection while Android still queues the host cannot cancel microphone ownership. */
    @Test
    fun connectionStopBeforeHostCreationPreservesQueuedDictation() {
        val harness = Harness(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        NotificationStreamForegroundService.pendingDictationOwner = { harness.conversationDictation.hasDurableSession }
        val context = RuntimeEnvironment.getApplication()
        val token = requireNotNull(harness.conversationDictation.notificationSessionToken)
        assertTrue(ConversationDictationForegroundService.start(context, token))
        assertTrue(NotificationStreamForegroundService.stop(context))
        assertNull(shadowOf(context).nextStoppedService)
        val command = shadowOf(context).nextStartedService
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(command, 0, 1)
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertEquals(1, harness.platform.sessionsCreated)
        harness.conversationDictation.cancel()
        lifecycle.destroy()
    }

    /** A background Stop cannot cancel a newer connection request queued before its Main callback. */
    @Test
    fun newerConnectionStartFencesQueuedBackgroundStop() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val context = RuntimeEnvironment.getApplication()
        val stopped = AtomicBoolean()
        val worker = Thread { stopped.set(NotificationStreamForegroundService.stop(context)) }
        worker.start()
        worker.join(5_000L)
        assertFalse(worker.isAlive)
        assertTrue(stopped.get())
        assertTrue(NotificationStreamForegroundService.start(context, ForegroundStartTrigger.UserToggle))
        shadowOf(Looper.getMainLooper()).idle()
        harness.conversationDictation.cancel()
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
        lifecycle.destroy()
    }

    /** A push-registration sync nudge does not supersede an already queued connection Stop. */
    @Test
    fun nativePushSyncDoesNotFenceQueuedConnectionStop() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val context = RuntimeEnvironment.getApplication()
        val stopped = AtomicBoolean()
        val worker = Thread { stopped.set(NotificationStreamForegroundService.stop(context)) }
        worker.start()
        worker.join(5_000L)
        assertFalse(worker.isAlive)
        assertTrue(stopped.get())
        assertTrue(NotificationStreamForegroundService.syncNativePushRegistration(context))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, service.foreground.connectionServiceType)
        assertTrue(harness.conversationDictation.hasDurableSession)
        harness.conversationDictation.cancel()
        assertTrue(shadowOf(service as Service).isForegroundStopped)
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), false)
        lifecycle.destroy()
    }

    /** Automatic wakes cannot turn a queued user Stop into a continuing connection lease. */
    @Test
    fun automaticWakeDoesNotFenceQueuedConnectionStop() {
        listOf(
            ForegroundStartTrigger.PushWake,
            ForegroundStartTrigger.SystemWake,
            ForegroundStartTrigger.CapabilityFallback,
        ).forEach { trigger ->
            val harness = installHost()
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
            service.onStartCommand(startIntent(service, harness), 0, 1)
            val context = RuntimeEnvironment.getApplication()
            val worker = Thread { NotificationStreamForegroundService.stop(context) }
            worker.start()
            worker.join(5_000L)
            assertFalse(worker.isAlive)
            assertTrue(NotificationStreamForegroundService.start(context, trigger))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, service.foreground.connectionServiceType)
            assertTrue(harness.conversationDictation.hasDurableSession)
            harness.conversationDictation.cancel()
            assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), false)
            lifecycle.destroy()
        }
    }

    /** The drawer describes sealed-audio work as transcription, through Starting and Listening. */
    @Test
    fun sealedAudioDrainNotificationNeverClaimsToBeListening() {
        val harness = installHost()
        val context = RuntimeEnvironment.getApplication()
        harness.platform.pendingCallerAudio = true
        harness.conversationDictation.send()
        harness.platform.listener.onError(ConversationDictationFailure.ProviderUnavailable)
        harness.conversationDictation.retry()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500L))
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Starting)
        listOf(false, true).forEach { ready ->
            if (ready) harness.platform.listener.onReady()
            assertFalse(harness.conversationDictation.captureInProgress)
            val notification =
                buildConversationDictationNotification(context, harness.conversationDictation) { _, _ ->
                    PendingIntent.getActivity(
                        context,
                        1,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            assertEquals(
                context.getString(R.string.dictation_processing),
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
            )
        }
        harness.conversationDictation.cancel()
    }

    /** Connection readiness is invalidated even when microphone ownership keeps the host alive. */
    @Test
    @Config(application = Application::class)
    fun releasingConnectionInvalidatesFallbackReadinessWhileDictationContinues() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        service.foreground.promoteConnection(ForegroundStartTrigger.CapabilityFallback)
        val requests = service.capabilityFallbackRequests
        requests.register(7L)
        assertEquals(setOf(7L), requests.onRuntimeStarted())
        service.foreground.releaseConnection()
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertTrue(requests.register(8L).isEmpty())
        assertEquals(setOf(8L), requests.onRuntimeUnavailable())
        harness.conversationDictation.cancel()
        lifecycle.destroy()
    }

    /** A queued user start cannot resurrect connection after the preference was switched off. */
    @Test
    @Config(application = Application::class)
    fun cancelledUserToggleReleasesOnlyConnectionWithoutBootstrapping() {
        val harness = installHost()
        val context = RuntimeEnvironment.getApplication()
        val prior = BackgroundConnectionPreferences.isEnabled(context)
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        try {
            service.onStartCommand(startIntent(service, harness), 0, 1)
            BackgroundConnectionPreferences.setEnabled(context, false)
            assertTrue(NotificationStreamForegroundService.start(context, ForegroundStartTrigger.UserToggle))
            service.onStartCommand(shadowOf(context).nextStartedService, 0, 2)
            val deadline = System.nanoTime() + 2_000_000_000L
            while (service.foreground.connectionServiceType != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10L)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals(0, service.foreground.connectionServiceType)
            assertTrue(harness.conversationDictation.hasDurableSession)
            assertFalse(shadowOf(service as Service).isForegroundStopped)
            harness.conversationDictation.cancel()
        } finally {
            lifecycle.destroy()
            BackgroundConnectionPreferences.setEnabled(context, prior)
        }
    }

    /** Actual host promotions share one Android service record and one ordered removal queue. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @Suppress("NestedBlockDepth") // Cross product of connection ownership, startup order and all three actions.
    fun delayedPostsCannotRestoreCompletedControlsForAnyActionOrStartupOrder() =
        runTest {
            listOf(false, true).forEach { connected ->
                listOf(false, true).forEach { connectionFirst ->
                    notificationActions.forEach { action ->
                        val harness = Harness(this)
                        ConversationDictationForegroundService.hostResolver = { harness }
                        val lifecycle =
                            Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
                        val service = lifecycle.get()
                        val manager = service.getSystemService(NotificationManager::class.java)
                        // Model both ActivityManager foreground post and cancel queues, unlike
                        // synchronous Robolectric. There is only one foreground record now.
                        var previousForegroundId = 0
                        NotificationStreamForegroundService.foregroundPublisher = { owner, notification, type ->
                            defaultPublisher(owner, notification, type)
                            val oldId = previousForegroundId
                            val newId = NotificationStreamForegroundService.foregroundNotificationId(notification)
                            previousForegroundId = newId
                            Handler(Looper.getMainLooper()).post {
                                if (oldId != 0 && oldId != newId) manager.cancel(oldId)
                                manager.notify(newId, notification)
                            }
                        }
                        NotificationStreamForegroundService.foregroundRemover = { owner ->
                            defaultRemover(owner)
                            val removedId = previousForegroundId
                            Handler(Looper.getMainLooper()).post {
                                manager.cancel(removedId)
                            }
                        }
                        if (connected && connectionFirst) {
                            service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
                        }
                        service.onStartCommand(startIntent(service, harness), 0, 1)
                        if (connected && !connectionFirst) {
                            service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
                        }
                        service.onStartCommand(actionCommand(service, harness, action), 0, 2)
                        harness.platform.listener.onResult("completed transcript")
                        runCurrent()
                        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                        Snapshot.sendApplyNotifications()
                        shadowOf(Looper.getMainLooper()).idle()
                        assertCompletedPresentation(manager, connected)
                        assertEquals(
                            if (action == ConversationDictationForegroundService.ACTION_SEND) 1 else 0,
                            harness.sent.size,
                        )
                        assertEquals(
                            if (action == ConversationDictationForegroundService.ACTION_PASTE) {
                                "completed transcript"
                            } else {
                                ""
                            },
                            harness.draft.text,
                        )
                        lifecycle.destroy()
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                }
            }
        }

    /** Connection commands cannot discard a dictation lease, and dictation cannot stop connection work. */
    @Test
    fun independentLeaseRemovalPreservesRemainingWork() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        service.foreground.releaseConnection()
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertEquals(
            "Dictation active",
            service.foreground
                .foregroundNotification()
                .extras
                .getCharSequence(Notification.EXTRA_TITLE)
                .toString(),
        )
        harness.conversationDictation.cancel()
        assertTrue(shadowOf(service as Service).isForegroundStopped)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        lifecycle.destroy()
    }

    /** Boot recovery never requests microphone; capture adds it only for its exact promoted lease. */
    @Test
    fun foregroundTypesTrackActualConnectionAndMicrophoneLeases() {
        val types = mutableListOf<Int>()
        NotificationStreamForegroundService.foregroundPublisher =
            modelForegroundIdReplacement { service, notification, type ->
                types += type
                defaultPublisher(service, notification, type)
            }
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.SystemWake)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, types.last())
        service.onStartCommand(startIntent(service, harness), 0, 1)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            types.last(),
        )
        harness.conversationDictation.cancel()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, types.last())
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
        lifecycle.destroy()
    }

    /** Adversarial API 34/35 policy coverage: background nudges cannot reassert microphone. */
    @Test
    @Config(sdk = [34, 35])
    fun backgroundConnectionChangesNeverReassertAuthorizedMicrophone() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        val types = mutableListOf<Int>()
        var backgrounded = false
        NotificationStreamForegroundService.foregroundPublisher =
            modelForegroundIdReplacement { owner, notification, type ->
                if (backgrounded && type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
                    error("Background microphone reassertion")
                }
                types += type
                defaultPublisher(owner, notification, type)
            }
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val authorizedCalls = types.size
        backgrounded = true
        harness.conversationDictation.onAppBackgrounded()
        listOf(ForegroundStartTrigger.PushWake, ForegroundStartTrigger.SystemWake, ForegroundStartTrigger.UserToggle)
            .forEach(service.foreground::promoteConnection)
        service.foreground.releaseConnection()
        assertEquals(authorizedCalls, types.size)
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertEquals("", harness.draft.text)
        harness.conversationDictation.cancel()
        assertTrue(shadowOf(service as Service).isForegroundStopped)
        lifecycle.destroy()
    }

    /** All commands completing before microphone promotion leave the existing connection alone. */
    @Test
    fun completionBeforePromotionKeepsConnectionAndNeverStartsCapture() {
        notificationActions.forEach { action ->
            val harness = Harness(autoReady = false)
            ConversationDictationForegroundService.hostResolver = { harness }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
            service.onStartCommand(actionCommand(service, harness, action), 0, 1)
            assertFalse(harness.conversationDictation.hasDurableSession)
            assertEquals(0, harness.platform.sessionsCreated)
            assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
            lifecycle.destroy()
        }
    }

    /** Presentation changes never reassert foreground microphone permission from the background. */
    @Test
    fun presentationRefreshDoesNotRestartForegroundOwnership() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        NotificationStreamForegroundService.foregroundPublisher = { _, _, _ ->
            throw SecurityException("background microphone promotion must not recur")
        }
        repeat(3) {
            harness.platform.listener.onReady()
            harness.platform.listener.onEndOfSpeech()
            harness.platform.listener.onResult("phrase")
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500L))
            assertTrue(harness.conversationDictation.hasDurableSession)
            assertEquals("", harness.draft.text)
        }
        harness.conversationDictation.paste()
        assertEquals("phrase phrase phrase", harness.draft.text)
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        lifecycle.destroy()
    }

    /** If the ordinary card is rejected, completed controls are removed rather than left behind. */
    @Test
    fun rejectedConnectionRestorationRemovesCompletedControls() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        NotificationStreamForegroundService.foregroundPublisher = { _, _, _ ->
            throw SecurityException("foreground revoked")
        }
        harness.conversationDictation.cancel()
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
        lifecycle.destroy()
    }

    /** Foreground return retries rejected completion narrowing even with an idle controller. */
    @Test
    fun completedDictationRetriesRejectedNarrowingOnForegroundReturn() {
        val types = mutableListOf<Int>()
        var reject = false
        NotificationStreamForegroundService.foregroundPublisher =
            modelForegroundIdReplacement { owner, notification, type ->
                if (reject) throw SecurityException("narrowing rejected")
                types += type
                defaultPublisher(owner, notification, type)
            }
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        reject = true
        harness.conversationDictation.cancel()
        assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
        assertTrue(types.last() and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
        reject = false
        NotificationStreamForegroundService.onAppForegrounded(harness.conversationDictation)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING, types.last())
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
        lifecycle.destroy()
    }

    /** A connection sharing special-use protection retains that legitimate type until its lease ends. */
    @Test
    fun connectionStartedDuringCaptureRetainsItsAcceptedSpecialUseType() {
        val types = mutableListOf<Int>()
        NotificationStreamForegroundService.foregroundPublisher =
            modelForegroundIdReplacement { owner, notification, type ->
                types += type
                defaultPublisher(owner, notification, type)
            }
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.onStartCommand(startIntent(service, harness), 0, 1)
        val promotions = types.size
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        assertEquals(promotions, types.size)
        harness.conversationDictation.cancel()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, types.last())
        service.foreground.promoteConnection(ForegroundStartTrigger.PushWake)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, types.last())
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
        service.foreground.releaseConnection()
        assertTrue(shadowOf(service as Service).isForegroundStopped)
        lifecycle.destroy()
    }

    /** Disabling connection also handles revocation of the remaining microphone type safely. */
    @Test
    fun connectionStopDoesNotReassertMicrophoneOrPasteText() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        service.onStartCommand(startIntent(service, harness), 0, 1)
        harness.platform.listener.onResult("preserved phrase")
        NotificationStreamForegroundService.foregroundPublisher = { _, _, _ ->
            throw SecurityException("microphone revoked")
        }
        service.foreground.releaseConnection()
        assertEquals("", harness.draft.text)
        assertTrue(harness.conversationDictation.hasDurableSession)
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        harness.conversationDictation.cancel()
        lifecycle.destroy()
    }

    /** A denied microphone promotion cannot cancel an existing, legitimate connection lease. */
    @Test
    fun microphonePromotionRejectionPreservesConnection() {
        val harness = Harness(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
        ConversationDictationForegroundService.foregroundPromoter = { _, _, _ ->
            throw SecurityException("microphone denied")
        }
        service.onStartCommand(startIntent(service, harness), 0, 1)
        assertFalse(harness.conversationDictation.hasDurableSession)
        assertEquals(0, harness.platform.sessionsCreated)
        assertFalse(shadowOf(service as Service).isForegroundStopped)
        assertCompletedPresentation(service.getSystemService(NotificationManager::class.java), true)
        lifecycle.destroy()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ConversationDictationNotificationSuppressionTest : ConversationDictationNotificationTestFixture() {
    /** A verified text-only fallback retires the dictation card even after a queued notification refresh. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rejectedBackgroundSendReleasesDictationCardAfterVerifiedDraftRecovery() =
        runTest {
            val harness = Harness(this)
            harness.acceptSend = false
            ConversationDictationForegroundService.hostResolver = { harness }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            val manager = service.getSystemService(NotificationManager::class.java)
            try {
                service.onStartCommand(startIntent(service, harness), 0, 1)
                harness.conversationDictation.onAppBackgrounded()
                service.onStartCommand(
                    actionCommand(service, harness, ConversationDictationForegroundService.ACTION_SEND),
                    0,
                    2,
                )
                harness.platform.listener.onResult("recognized")
                runCurrent()
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                assertEquals("recognized", harness.draft.text)
                assertTrue(harness.sent.isEmpty())
                assertFalse(harness.conversationDictation.hasDurableSession)
                assertFalse(harness.conversationDictation.foregroundMicrophoneRequired)
                service.foreground.dictation.refreshNotification()
                assertFalse(
                    manager.activeNotifications.any {
                        it.notification.channelId == ConversationDictationForegroundService.CHANNEL_ID
                    },
                )
                harness.conversationDictation.onAppForegrounded()
                runCurrent()
                assertFalse(harness.conversationDictation.hasDurableSession)
                assertEquals("recognized", harness.draft.text)
            } finally {
                lifecycle.destroy()
            }
        }

    /** A rejected narrowing cannot rely on a blocked ordinary channel to retire microphone controls. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rejectedIdentitySwitchUsesAControlFreeCardOnTheAuthorizedChannel() =
        runTest {
            val harness = Harness(this)
            ConversationDictationForegroundService.hostResolver = { harness }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            val manager = service.getSystemService(NotificationManager::class.java)
            try {
                service.onStartCommand(startIntent(service, harness), 0, 1)
                service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
                val ordinary =
                    NotificationChannel(
                        BackgroundConnectionNotification.CHANNEL_ID,
                        service.getString(R.string.notification_channel_background_connection),
                        NotificationManager.IMPORTANCE_NONE,
                    )
                manager.createNotificationChannel(ordinary)
                NotificationStreamForegroundService.foregroundPublisher =
                    modelForegroundIdReplacement { owner, notification, type ->
                        if (notification.channelId == BackgroundConnectionNotification.CHANNEL_ID) {
                            throw SecurityException("narrowing rejected")
                        }
                        defaultPublisher(owner, notification, type)
                    }
                harness.conversationDictation.paste()
                harness.platform.listener.onResult("recognized")
                runCurrent()
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                val remaining = manager.activeNotifications.single().notification
                assertEquals(ConversationDictationForegroundService.CHANNEL_ID, remaining.channelId)
                assertTrue(remaining.actions.isNullOrEmpty())
                assertNull(remaining.contentView)
                assertTrue(service.foreground.connectionServiceType != 0)
                assertFalse(shadowOf(service as Service).isForegroundStopped)
            } finally {
                lifecycle.destroy()
            }
        }

    /** Android cancels the previous foreground ID even if the replacement card is suppressed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aRejectedOrdinaryCardCannotKeepCompletedDictationControls() =
        runTest {
            notificationActions.forEach { action ->
                val harness = Harness(this)
                ConversationDictationForegroundService.hostResolver = { harness }
                val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
                val service = lifecycle.get()
                val manager = service.getSystemService(NotificationManager::class.java)
                var associatedId = 0
                NotificationStreamForegroundService.foregroundPublisher = { owner, notification, type ->
                    val before = manager.activeNotifications.associate { it.id to it.notification }
                    val nextId = NotificationStreamForegroundService.foregroundNotificationId(notification)
                    defaultPublisher(owner, notification, type)
                    if (notification.channelId == BackgroundConnectionNotification.CHANNEL_ID) {
                        // A disabled channel/rate rejection leaves an existing key untouched.
                        manager.cancel(nextId)
                        before[nextId]?.let { manager.notify(nextId, it) }
                    }
                    // This is ActiveServices' system cancellation, not an app cancelling a live FGS.
                    if (associatedId != 0 && associatedId != nextId) manager.cancel(associatedId)
                    associatedId = nextId
                }
                try {
                    service.onStartCommand(startIntent(service, harness), 0, 1)
                    service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)
                    assertTrue(
                        manager.activeNotifications.any {
                            it.notification.channelId == ConversationDictationForegroundService.CHANNEL_ID
                        },
                    )
                    service.onStartCommand(actionCommand(service, harness, action), 0, 2)
                    if (action != ConversationDictationForegroundService.ACTION_CANCEL) {
                        harness.platform.listener.onResult("recognized")
                    }
                    runCurrent()
                    Snapshot.sendApplyNotifications()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                    assertFalse(
                        manager.activeNotifications.any {
                            it.notification.channelId == ConversationDictationForegroundService.CHANNEL_ID
                        },
                    )
                    assertTrue(service.foreground.connectionServiceType != 0)
                    assertFalse(shadowOf(service as Service).isForegroundStopped)
                } finally {
                    lifecycle.destroy()
                }
            }
        }
}

internal abstract class ConversationDictationNotificationTestFixture {
    @Before
    fun modelAndroidForegroundIdentityReplacement() {
        NotificationStreamForegroundService.foregroundPublisher = modelForegroundIdReplacement(defaultPublisher)
    }

    protected class Harness(
        scope: CoroutineScope? = null,
        preference: ConversationDictationDeliveryMode = ConversationDictationDeliveryMode.PasteIntoDraft,
        autoReady: Boolean = true,
    ) : ConversationDictationServiceHost {
        val platform = FakePlatform()
        var draft = TextFieldValue("")
        var revision = 0L
        var acceptSend = true
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
                    (acceptSend && request.beginDispatch()).also { if (it) sent += request.payload }
                },
            )

        init {
            conversationDictation.requestStart("account", "group", TextFieldValue(""))
        }
    }

    /** Restores process-wide service seams so each Robolectric case starts isolated. */
    @After
    fun restoreResolver() {
        ConversationDictationForegroundService.hostResolver = defaultResolver
        ConversationDictationForegroundService.foregroundPromoter = defaultForegroundPromoter
        NotificationStreamForegroundService.foregroundPublisher = defaultPublisher
        NotificationStreamForegroundService.foregroundRemover = defaultRemover
        NotificationStreamForegroundService.pendingDictationOwner = defaultPendingOwner
    }

    protected fun assertCompletedPresentation(
        manager: NotificationManager,
        connected: Boolean,
    ) {
        if (!connected) {
            assertTrue(manager.activeNotifications.isEmpty())
        } else {
            val notification = manager.activeNotifications.single().notification
            assertEquals(
                "White Noise is connected",
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            )
            assertNull(notification.contentView)
            assertTrue(notification.actions.isNullOrEmpty())
        }
    }

    protected fun actionCommand(
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

    /** Installs a fresh process-owner harness into the service resolver seam. */
    protected fun installHost(): Harness =
        Harness().also { installed ->
            ConversationDictationForegroundService.hostResolver = { installed }
        }

    protected fun startIntent(
        service: Service,
        harness: Harness,
    ): Intent =
        Intent(service, service::class.java)
            .putExtra(
                ConversationDictationForegroundService.EXTRA_SESSION_TOKEN,
                requireNotNull(harness.conversationDictation.notificationSessionToken),
            )

    protected class FakePlatform : ConversationDictationPlatform {
        var sessionsCreated = 0
        var pendingCallerAudio = false

        override fun callerAudioHasPending(): Boolean = pendingCallerAudio

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

    protected companion object {
        val notificationActions =
            listOf(
                ConversationDictationForegroundService.ACTION_CANCEL,
                ConversationDictationForegroundService.ACTION_PASTE,
                ConversationDictationForegroundService.ACTION_SEND,
            )
        val defaultPendingOwner = NotificationStreamForegroundService.pendingDictationOwner
        val defaultPublisher = NotificationStreamForegroundService.foregroundPublisher
        val defaultRemover = NotificationStreamForegroundService.foregroundRemover
        val defaultResolver = ConversationDictationForegroundService.hostResolver
        val defaultForegroundPromoter = ConversationDictationForegroundService.foregroundPromoter
    }
}
