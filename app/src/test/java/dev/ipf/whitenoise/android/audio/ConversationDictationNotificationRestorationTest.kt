package dev.ipf.whitenoise.android.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionNotification
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationDictationNotificationRestorationTest {
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
    }

    /** Restores process-wide service seams so each Robolectric case starts isolated. */
    @After
    fun restoreResolver() {
        ConversationDictationForegroundService.hostResolver = defaultResolver
        ConversationDictationForegroundService.foregroundPromoter = defaultForegroundPromoter
        BackgroundConnectionNotification.markForegroundStopped()
    }

    /** Android queues foreground posts; a completion tap must not queue stale controls behind cleanup. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun completionActionsCannotRepostControlsAfterConnectionRestoration() =
        runTest {
            notificationActions.forEach { action ->
                BackgroundConnectionNotification.markForegroundActive()
                val harness = Harness(this)
                ConversationDictationForegroundService.hostResolver = { harness }
                ConversationDictationForegroundService.foregroundPromoter = { service, notification ->
                    defaultForegroundPromoter(service, notification)
                    // ServiceRecord.postNotification captures the notification, then posts it on
                    // the system handler. STOP_FOREGROUND_REMOVE cannot cancel a shared ID
                    // while the connection service still owns it.
                    Handler(Looper.getMainLooper()).post {
                        service
                            .getSystemService(NotificationManager::class.java)
                            .notify(BackgroundConnectionNotification.NOTIFICATION_ID, notification)
                    }
                }
                val lifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
                val service = lifecycle.get()
                service.onStartCommand(startIntent(service, harness), 0, 1)
                shadowOf(Looper.getMainLooper()).idle()

                service.onStartCommand(actionCommand(service, harness, action), 0, 2)
                harness.platform.listener.onResult("completed transcript")
                runCurrent()
                assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idle()

                val notification =
                    service
                        .getSystemService(NotificationManager::class.java)
                        .activeNotifications
                        .single()
                        .notification
                assertEquals(
                    "White Noise is connected",
                    notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
                )
                assertNull(notification.contentView)
                lifecycle.destroy()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }

    /** Every completion queued before the initial service start ends without capturing or posting controls. */
    @Test
    fun completionBeforeFirstPromotionNeverStartsMicrophone() {
        notificationActions.forEach { action ->
            BackgroundConnectionNotification.markForegroundActive()
            val harness = Harness(autoReady = false)
            ConversationDictationForegroundService.hostResolver = { harness }
            val lifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
            val service = lifecycle.get()

            service.onStartCommand(actionCommand(service, harness, action), 0, 1)

            assertFalse(harness.conversationDictation.hasDurableSession)
            assertEquals(0, harness.platform.sessionsCreated)
            assertNull(shadowOf(service as Service).lastForegroundNotification)
            val notification =
                service
                    .getSystemService(NotificationManager::class.java)
                    .activeNotifications
                    .single()
                    .notification
            assertEquals(
                "White Noise is connected",
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
            )
            lifecycle.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** A delayed initial foreground post also precedes restoration when completion comes from the app. */
    @Test
    fun connectionRestorationFollowsDelayedInitialControlsPost() {
        val harness = installHost()
        val lifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val service = lifecycle.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        BackgroundConnectionNotification.markForegroundActive(service) { notification ->
            Handler(Looper.getMainLooper()).post {
                manager.notify(BackgroundConnectionNotification.NOTIFICATION_ID, notification)
            }
        }
        ConversationDictationForegroundService.foregroundPromoter = { owner, notification ->
            defaultForegroundPromoter(owner, notification)
            Handler(Looper.getMainLooper()).post {
                manager.notify(BackgroundConnectionNotification.NOTIFICATION_ID, notification)
            }
        }

        service.onStartCommand(startIntent(service, harness), 0, 1)
        harness.conversationDictation.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        val notification = manager.activeNotifications.single().notification
        assertEquals(
            "White Noise is connected",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertNull(notification.contentView)
        lifecycle.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Destruction of an old connection service cannot withdraw a replacement's restoration callback. */
    @Test
    fun oldConnectionOwnerCannotClearReplacementRestoration() {
        val context = RuntimeEnvironment.getApplication()
        val oldOwner = Any()
        val replacementOwner = Any()
        var restored = false
        BackgroundConnectionNotification.markForegroundActive(oldOwner) {}
        BackgroundConnectionNotification.markForegroundActive(replacementOwner) { restored = true }

        BackgroundConnectionNotification.markForegroundStopped(oldOwner)

        assertTrue(BackgroundConnectionNotification.restoreIfForeground(context))
        assertTrue(restored)
    }

    /** Completion updates the real connection service's foreground card, not only NotificationManager. */
    @Test
    @Config(application = WhiteNoiseApplication::class)
    fun completionRestoresRealConnectionServiceForegroundNotification() {
        val context = RuntimeEnvironment.getApplication()
        val connectionLifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val connectionService = connectionLifecycle.get()
        val harness = installHost()
        val dictationLifecycle = Robolectric.buildService(ConversationDictationForegroundService::class.java).create()
        val dictationService = dictationLifecycle.get()
        dictationService.onStartCommand(startIntent(dictationService, harness), 0, 1)
        assertTrue(NotificationStreamForegroundService.start(context))
        connectionService.onStartCommand(shadowOf(context).nextStartedService, 0, 1)
        val original = shadowOf(connectionService as Service).lastForegroundNotification
        assertEquals(
            "Dictation active",
            original.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )

        harness.conversationDictation.cancel()

        val notification = shadowOf(connectionService as Service).lastForegroundNotification
        assertEquals(
            "White Noise is connected",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertNull(notification.contentView)
        dictationLifecycle.destroy()
        connectionLifecycle.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Android rejection of a foreground refresh still replaces the controls using notify. */
    @Test
    fun rejectedConnectionRestorationFallsBackToPlainConnectionCard() {
        val context = RuntimeEnvironment.getApplication()
        BackgroundConnectionNotification.markForegroundActive(Any()) { throw IllegalStateException("rejected") }

        assertTrue(BackgroundConnectionNotification.restoreIfForeground(context))

        val notification =
            context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single()
                .notification
        assertEquals(
            "White Noise is connected",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertNull(notification.contentView)
    }

    private fun actionCommand(
        service: ConversationDictationForegroundService,
        harness: Harness,
        action: String,
    ): Intent =
        shadowOf(
            service.actionIntent(action, requireNotNull(harness.conversationDictation.notificationSessionToken)),
        ).savedIntent

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
