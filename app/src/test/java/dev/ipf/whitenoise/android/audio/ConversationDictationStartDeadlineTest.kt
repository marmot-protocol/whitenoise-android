package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionNotification
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.QueuedStartShadowService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Dictation starts that end before onStartCommand still satisfy Android's foreground start deadline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [QueuedStartShadowService::class])
internal class ConversationDictationStartDeadlineTest : ConversationDictationForegroundTestCase() {
    /** Cancelling dictation before onStartCommand runs still satisfies the start deadline and stops cleanly. */
    @Test
    fun cancelledStartSatisfiesDeadlineAndStopsWithoutLeavingNotification() {
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val queuedToken = requireNotNull(harness.conversationDictation.notificationSessionToken)
        harness.conversationDictation.cancel()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()

        val result = service.onStartCommand(dictationStartIntent(service, queuedToken), 0, 7)

        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        val published = shadow.publishedNotifications.single()
        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(0, harness.platform.sessionsCreated)
        assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, published.id)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, published.serviceType)
        assertTrue(published.notification.actions.isNullOrEmpty())
        assertEquals(
            listOf(
                "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                "stopSelfResult:7:true",
                "stopForeground:true",
            ),
            shadow.events,
        )
        assertNull(shadow.lastForegroundNotification)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(7, shadow.stopSelfResultId)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        lifecycle.destroy()
    }

    /** A stale dictation start keeps its action-free placeholder when a newer start is already queued. */
    @Test
    fun staleStartKeepsActionFreePlaceholderThroughQueuedReplacementHandoff() {
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val staleToken = requireNotNull(harness.conversationDictation.notificationSessionToken)
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        val liveToken = requireNotNull(harness.conversationDictation.notificationSessionToken)
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        shadow.rejectedStopSelfIds += 1

        service.onStartCommand(dictationStartIntent(service, staleToken), 0, 1)

        val placeholder = shadow.publishedNotifications.single()
        assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, placeholder.id)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, placeholder.serviceType)
        assertTrue(placeholder.notification.actions.isNullOrEmpty())
        assertEquals(
            listOf("startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}", "stopSelfResult:1:false"),
            shadow.events,
        )
        assertTrue(shadow.stopForegroundCalls.isEmpty())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertNotNull(shadow.lastForegroundNotification)
        assertEquals(0, harness.platform.sessionsCreated)

        service.onStartCommand(dictationStartIntent(service, liveToken), 0, 2)

        val micType =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(2, shadow.publishedNotifications.size)
        assertEquals(NotificationStreamForegroundService.DICTATION_NOTIFICATION_ID, shadow.publishedNotifications[1].id)
        assertEquals(micType, shadow.publishedNotifications[1].serviceType)
        assertEquals(1, harness.platform.sessionsCreated)
        assertTrue(harness.conversationDictation.ownsMicrophone)
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals(
            listOf(NotificationStreamForegroundService.DICTATION_NOTIFICATION_ID),
            service.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id },
        )

        harness.conversationDictation.cancel()

        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(2, shadow.stopSelfResultId)
        assertNull(shadow.lastForegroundNotification)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        lifecycle.destroy()
    }

    /** A stale capture start arriving while Keep Connected is already foreground does not churn or stop it. */
    @Test
    fun staleQueuedStartPreservesAlreadyForegroundConnectionWithoutChurn() {
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val queuedToken = requireNotNull(harness.conversationDictation.notificationSessionToken)
        harness.conversationDictation.cancel()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)

        val publishedAfterConnection = mutableListOf<Int>()
        val basePublisher = NotificationStreamForegroundService.foregroundPublisher
        NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
            publishedAfterConnection += type
            basePublisher(host, notification, type)
        }

        service.onStartCommand(dictationStartIntent(service, queuedToken), 0, 2)

        val shadow = shadowOf(service as Service)
        assertTrue(publishedAfterConnection.isEmpty())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertTrue(
            service.getSystemService(NotificationManager::class.java).activeNotifications.any {
                it.id == BackgroundConnectionNotification.NOTIFICATION_ID
            },
        )
        lifecycle.destroy()
    }

    /** A stale queued replacement reuses start A's placeholder without republishing before stopping cleanly. */
    @Test
    fun staleQueuedReplacementReusesExistingPlaceholderAndStopsCleanly() {
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val staleTokenA = requireNotNull(harness.conversationDictation.notificationSessionToken)
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        val staleTokenB = requireNotNull(harness.conversationDictation.notificationSessionToken)
        harness.conversationDictation.cancel()
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        shadow.rejectedStopSelfIds += 1

        service.onStartCommand(dictationStartIntent(service, staleTokenA), 0, 1)
        assertEquals(1, shadow.publishedNotifications.size)
        assertTrue(shadow.stopForegroundCalls.isEmpty())

        service.onStartCommand(dictationStartIntent(service, staleTokenB), 0, 2)

        val published = shadow.publishedNotifications.single()
        assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, published.id)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, published.serviceType)
        assertTrue(published.notification.actions.isNullOrEmpty())
        assertEquals(
            listOf(
                "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                "stopSelfResult:1:false",
                "stopSelfResult:2:true",
                "stopForeground:true",
            ),
            shadow.events,
        )
        assertStoppedCleanly(service, shadow, expectedStartId = 2)
        lifecycle.destroy()
    }

    /** A microphone-rejected queued replacement reuses start A's placeholder and detaches foreground cleanly. */
    @Test
    fun microphoneRejectedQueuedReplacementReusesPlaceholderAndStopsCleanly() {
        val harness = DictationForegroundTestHost(autoReady = false)
        ConversationDictationForegroundService.hostResolver = { harness }
        val staleTokenA = requireNotNull(harness.conversationDictation.notificationSessionToken)
        harness.conversationDictation.cancel()
        harness.conversationDictation.requestStart("account", "replacement", TextFieldValue(""))
        val liveTokenB = requireNotNull(harness.conversationDictation.notificationSessionToken)
        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        shadow.rejectedStopSelfIds += 1

        service.onStartCommand(dictationStartIntent(service, staleTokenA), 0, 1)
        assertEquals(1, shadow.publishedNotifications.size)
        assertTrue(shadow.stopForegroundCalls.isEmpty())

        val attemptedAfterA = mutableListOf<Int>()
        val basePublisher = NotificationStreamForegroundService.foregroundPublisher
        NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
            attemptedAfterA += type
            if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
                throw SecurityException("mic foreground promotion blocked")
            }
            basePublisher(host, notification, type)
        }

        service.onStartCommand(dictationStartIntent(service, liveTokenB), 0, 2)

        val micType =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(listOf(micType), attemptedAfterA)
        assertEquals(1, shadow.publishedNotifications.size)
        assertEquals(
            listOf(
                "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                "stopSelfResult:1:false",
                "stopSelfResult:2:true",
                "stopForeground:true",
            ),
            shadow.events,
        )
        assertFalse(harness.conversationDictation.hasDurableSession)
        assertStoppedCleanly(service, shadow, expectedStartId = 2)
        lifecycle.destroy()
    }

    /** Asserts the service accepted [expectedStartId], removed foreground, and cleared all notifications. */
    private fun assertStoppedCleanly(
        service: NotificationStreamForegroundService,
        shadow: QueuedStartShadowService,
        expectedStartId: Int,
    ) {
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(expectedStartId, shadow.stopSelfResultId)
        assertNull(shadow.lastForegroundNotification)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    /** Builds a dictation ACTION_START intent carrying [token]. */
    private fun dictationStartIntent(
        service: NotificationStreamForegroundService,
        token: String,
    ): Intent =
        Intent(service, service::class.java)
            .setAction(ConversationDictationForegroundService.ACTION_START)
            .putExtra(ConversationDictationForegroundService.EXTRA_SESSION_TOKEN, token)

    /** Verifies a stale queued start satisfies the FGS deadline with specialUse and stops without mic capture. */
    @Test
    fun queuedStartAfterControllerFailureSatisfiesDeadlineWithoutMicrophone() {
        val harness = installHost()
        harness.failRecognition()
        assertTrue(harness.conversationDictation.hasPendingSession)
        assertFalse(harness.conversationDictation.hasDurableSession)
        val serviceController = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = serviceController.get()

        val result = service.onStartCommand(startIntent(service, harness), 0, 1)

        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        assertEquals(Service.START_NOT_STICKY, result)
        assertPlaceholderPublishedAndRemoved(service, shadow, expectedStartId = 1)
        assertFalse(harness.conversationDictation.ownsMicrophone)
        serviceController.destroy()
    }

    /** Verifies mic promotion rejection satisfies the start deadline before stopping once and failing capture. */
    @Test
    fun foregroundPromotionRejectionCancelsCaptureAndStopsTheService() {
        listOf<RuntimeException>(
            SecurityException("blocked"),
            ForegroundServiceStartNotAllowedException("blocked"),
        ).forEachIndexed { index, failure ->
            assertMicrophonePromotionRejectionSatisfiesStartDeadline(failure, startId = index + 1)
        }
    }

    /** Rejects only microphone promotion and asserts specialUse promotion precedes stopSelfResult and removal. */
    private fun assertMicrophonePromotionRejectionSatisfiesStartDeadline(
        failure: RuntimeException,
        startId: Int,
    ) {
        val basePublisher = NotificationStreamForegroundService.foregroundPublisher
        val baseRemover = NotificationStreamForegroundService.foregroundRemover
        try {
            ShadowLog.clear()
            val harness = DictationForegroundTestHost(autoReady = false)
            ConversationDictationForegroundService.hostResolver = { harness }
            ConversationDictationForegroundService.foregroundPromoter = defaultForegroundPromoter
            val lifecycleEvents = mutableListOf<String>()
            NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
                assertFalse(shadowOf(host as Service).isStoppedBySelf)
                lifecycleEvents += "publish:$type"
                if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) throw failure
                basePublisher(host, notification, type)
            }
            NotificationStreamForegroundService.foregroundRemover = { host ->
                assertTrue(shadowOf(host as Service).isStoppedBySelf)
                lifecycleEvents += "remove"
                baseRemover(host)
            }
            val serviceController =
                Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = serviceController.get()

            val result = service.onStartCommand(startIntent(service, harness), 0, startId)

            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            val micType =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            assertEquals(Service.START_NOT_STICKY, result)
            assertEquals(
                listOf("publish:$micType", "publish:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}", "remove"),
                lifecycleEvents,
            )
            assertTrue(harness.conversationDictation.state is ConversationDictationState.Failed)
            assertFalse(harness.conversationDictation.hasDurableSession)
            assertPlaceholderPublishedAndRemoved(service, shadow, expectedStartId = startId)
            assertEquals(1, foregroundNotificationClosedCount())
            serviceController.destroy()
        } finally {
            NotificationStreamForegroundService.foregroundPublisher = basePublisher
            NotificationStreamForegroundService.foregroundRemover = baseRemover
        }
    }

    /** Re-entrant cancellation during promotion still satisfies the unmet start deadline before stopping once. */
    @Test
    fun ownershipLostDuringPromotionCannotBePublishedOrStartCapture() {
        val basePublisher = NotificationStreamForegroundService.foregroundPublisher
        val baseRemover = NotificationStreamForegroundService.foregroundRemover
        try {
            ShadowLog.clear()
            val harness = DictationForegroundTestHost(autoReady = false)
            ConversationDictationForegroundService.hostResolver = { harness }
            val lifecycleEvents = mutableListOf<String>()
            NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
                assertFalse(shadowOf(host as Service).isStoppedBySelf)
                lifecycleEvents += "publish:$type"
                basePublisher(host, notification, type)
            }
            NotificationStreamForegroundService.foregroundRemover = { host ->
                assertTrue(shadowOf(host as Service).isStoppedBySelf)
                lifecycleEvents += "remove"
                baseRemover(host)
            }
            ConversationDictationForegroundService.foregroundPromoter = { _, _, _ ->
                harness.conversationDictation.cancel()
            }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()

            service.onStartCommand(startIntent(service, harness), 0, 1)

            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            assertEquals(
                listOf("publish:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}", "remove"),
                lifecycleEvents,
            )
            assertTrue(harness.conversationDictation.state is ConversationDictationState.Idle)
            assertEquals(0, harness.platform.sessionsCreated)
            assertPlaceholderPublishedAndRemoved(service, shadow, expectedStartId = 1)
            assertEquals(1, foregroundNotificationClosedCount())
            lifecycle.destroy()
        } finally {
            NotificationStreamForegroundService.foregroundPublisher = basePublisher
            NotificationStreamForegroundService.foregroundRemover = baseRemover
        }
    }

    /** Counts how many times teardown invoked stopSelfResult on the dictation diagnostic stream. */
    private fun foregroundNotificationClosedCount(): Int =
        ShadowLog
            .getLogsForTag("WNDictation")
            .mapNotNull { DictationDiagnosticSchema.fields(it.msg) }
            .count { it["event"] == "foreground_notification_closed" }

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

    /** An unbound queued start satisfies the FGS deadline with specialUse and stops without opening the mic. */
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
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        assertEquals(0, harness.platform.sessionsCreated)
        assertPlaceholderPublishedAndRemoved(service, shadow, expectedStartId = 1)
        lifecycle.destroy()
        assertTrue(harness.conversationDictation.hasDurableSession)
        harness.conversationDictation.cancel()
    }

    /** Asserts an action-free specialUse placeholder published before stopSelfResult accepted and removed it. */
    private fun assertPlaceholderPublishedAndRemoved(
        service: NotificationStreamForegroundService,
        shadow: QueuedStartShadowService,
        expectedStartId: Int,
    ) {
        val published = shadow.publishedNotifications.single()
        assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, published.id)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, published.serviceType)
        assertTrue(published.notification.actions.isNullOrEmpty())
        assertEquals(
            listOf(
                "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                "stopSelfResult:$expectedStartId:true",
                "stopForeground:true",
            ),
            shadow.events,
        )
        assertNull(shadow.lastForegroundNotification)
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(expectedStartId, shadow.stopSelfResultId)
        assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
