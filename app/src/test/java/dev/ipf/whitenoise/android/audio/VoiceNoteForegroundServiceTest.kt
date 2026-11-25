package dev.ipf.whitenoise.android.audio

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionNotification
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.QueuedStartShadowService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Verifies voice-note foreground start commands satisfy Android's start deadline before stopping. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [QueuedStartShadowService::class])
class VoiceNoteForegroundServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val chat = VoiceNoteKey("account", "aa".repeat(32))

    /** Installs Android foreground ID replacement semantics for each Robolectric service test. */
    @Before
    fun installForegroundReplacement() {
        NotificationStreamForegroundService.foregroundPublisher = modelForegroundIdReplacement(defaultPublisher)
    }

    /** Restores process-wide foreground seams after each test. */
    @After
    fun restoreSeams() {
        VoiceNoteForegroundService.ownerResolver = defaultOwnerResolver
        NotificationStreamForegroundService.foregroundPublisher = defaultPublisher
        NotificationStreamForegroundService.foregroundRemover = defaultRemover
    }

    /** A quick hold-and-release ending before onStartCommand satisfies the FGS deadline and stops cleanly. */
    @Test
    fun quickTakeEndingBeforeStartCommandSatisfiesDeadlineAndStopsCleanly() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val recorder = fixture.owner.bind(chat) { true }
            assertTrue(recorder.start())
            runCurrent()
            val queuedToken = requireNotNull(fixture.startedTokens.singleOrNull())

            recorder.stop()
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()
            assertFalse(recorder.isRecording)

            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()

            val result = service.onStartCommand(startIntent(service, queuedToken), 0, 3)

            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            val published = shadow.publishedNotifications.single()
            assertEquals(Service.START_NOT_STICKY, result)
            assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, published.id)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, published.serviceType)
            assertTrue(published.notification.actions.isNullOrEmpty())
            assertEquals(
                listOf(
                    "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                    "stopSelfResult:3:true",
                    "stopForeground:true",
                ),
                shadow.events,
            )
            assertNull(shadow.lastForegroundNotification)
            assertTrue(shadow.isForegroundStopped)
            assertTrue(shadow.notificationShouldRemoved)
            assertTrue(shadow.isStoppedBySelf)
            assertEquals(3, shadow.stopSelfResultId)
            assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            assertFalse(recorder.isRecording)
            lifecycle.destroy()
            fixture.release()
        }

    /** A rejected microphone promotion interrupts the take and still satisfies the start deadline. */
    @Test
    fun failedVoicePromotionInterruptsTakeAndSatisfiesStartDeadline() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val recorder = fixture.owner.bind(chat) { true }
            assertTrue(recorder.start())
            runCurrent()
            val queuedToken = requireNotNull(fixture.startedTokens.singleOrNull())
            assertTrue(recorder.isRecording)

            val attemptedTypes = mutableListOf<Int>()
            val basePublisher = NotificationStreamForegroundService.foregroundPublisher
            NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
                attemptedTypes += type
                if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0) {
                    throw SecurityException("mic foreground promotion blocked")
                }
                basePublisher(host, notification, type)
            }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()

            val result = service.onStartCommand(startIntent(service, queuedToken), 0, 5)
            runCurrent()

            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            val published = shadow.publishedNotifications.single()
            assertEquals(Service.START_NOT_STICKY, result)
            assertEquals(
                listOf(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ),
                attemptedTypes,
            )
            assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, published.id)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, published.serviceType)
            assertTrue(published.notification.actions.isNullOrEmpty())
            assertEquals(
                listOf(
                    "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                    "stopSelfResult:5:true",
                    "stopForeground:true",
                ),
                shadow.events,
            )
            assertFalse(recorder.isRecording)
            assertNull(shadow.lastForegroundNotification)
            assertTrue(shadow.isForegroundStopped)
            assertTrue(shadow.notificationShouldRemoved)
            assertTrue(shadow.isStoppedBySelf)
            assertEquals(5, shadow.stopSelfResultId)
            assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            lifecycle.destroy()
            fixture.release()
        }

    /** A platform rejection of even the specialUse placeholder is contained and still stops the service. */
    @Test
    fun rejectedPlaceholderStillStopsWithoutThrowing() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val recorder = fixture.owner.bind(chat) { true }
            assertTrue(recorder.start())
            runCurrent()
            val queuedToken = requireNotNull(fixture.startedTokens.singleOrNull())
            recorder.cancel()
            runCurrent()

            NotificationStreamForegroundService.foregroundPublisher = { _, _, _ ->
                throw ForegroundServiceStartNotAllowedException("background blocked")
            }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()

            val result = service.onStartCommand(startIntent(service, queuedToken), 0, 9)

            val shadow = shadowOf(service as Service)
            assertEquals(Service.START_NOT_STICKY, result)
            assertNull(shadow.lastForegroundNotification)
            assertTrue(shadow.isStoppedBySelf)
            assertEquals(9, shadow.stopSelfResultId)
            assertTrue(service.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            lifecycle.destroy()
            fixture.release()
        }

    /** Plain startService STOP and DISCARD actions stop an idle service without promoting foreground. */
    @Test
    fun plainStopAndDiscardCommandsDoNotPromoteForeground() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val publishedTypes = mutableListOf<Int>()
            val basePublisher = NotificationStreamForegroundService.foregroundPublisher
            NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
                publishedTypes += type
                basePublisher(host, notification, type)
            }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()

            service.onStartCommand(actionIntent(service, VoiceNoteForegroundService.ACTION_STOP, "stale-token"), 0, 1)
            service.onStartCommand(
                actionIntent(service, VoiceNoteForegroundService.ACTION_DISCARD, "stale-token"),
                0,
                2,
            )

            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            assertTrue(publishedTypes.isEmpty())
            assertNull(shadow.lastForegroundNotification)
            assertTrue(shadow.stopForegroundCalls.isEmpty())
            assertTrue(shadow.isStoppedBySelf)
            assertEquals(2, shadow.stopSelfResultId)
            lifecycle.destroy()
            fixture.release()
        }

    /** Stale voice STOP and DISCARD actions never stop or churn an active Keep Connected foreground lease. */
    @Test
    fun staleStopAndDiscardPreserveActiveConnection() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)

            val publishedAfterConnection = mutableListOf<Int>()
            val basePublisher = NotificationStreamForegroundService.foregroundPublisher
            NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
                publishedAfterConnection += type
                basePublisher(host, notification, type)
            }

            service.onStartCommand(actionIntent(service, VoiceNoteForegroundService.ACTION_STOP, "stale-token"), 0, 2)
            service.onStartCommand(
                actionIntent(service, VoiceNoteForegroundService.ACTION_DISCARD, "stale-token"),
                0,
                3,
            )

            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            assertTrue(publishedAfterConnection.isEmpty())
            assertTrue(shadow.stopForegroundCalls.isEmpty())
            assertFalse(shadow.isStoppedBySelf)
            lifecycle.destroy()
            fixture.release()
        }

    /** A stale voice START arriving while Keep Connected is already foreground does not churn or stop it. */
    @Test
    fun staleVoiceStartPreservesAlreadyForegroundConnectionWithoutChurn() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val recorder = fixture.owner.bind(chat) { true }
            assertTrue(recorder.start())
            runCurrent()
            val queuedToken = requireNotNull(fixture.startedTokens.singleOrNull())
            recorder.cancel()
            runCurrent()

            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            service.foreground.promoteConnection(ForegroundStartTrigger.UserToggle)

            val publishedAfterConnection = mutableListOf<Int>()
            val basePublisher = NotificationStreamForegroundService.foregroundPublisher
            NotificationStreamForegroundService.foregroundPublisher = { host, notification, type ->
                publishedAfterConnection += type
                basePublisher(host, notification, type)
            }

            service.onStartCommand(startIntent(service, queuedToken), 0, 4)

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
            fixture.release()
        }

    /** A stale voice start keeps its action-free placeholder when a newer live take is already queued. */
    @Test
    fun staleVoiceStartKeepsPlaceholderThroughQueuedReplacementHandoff() =
        runTest {
            val fixture = Fixture(this)
            VoiceNoteForegroundService.ownerResolver = { fixture.owner }
            val recorder = fixture.owner.bind(chat) { true }
            assertTrue(recorder.start())
            runCurrent()
            val staleToken = fixture.startedTokens.single()
            recorder.cancel()
            runCurrent()
            assertTrue(recorder.start())
            runCurrent()
            val liveToken = fixture.startedTokens.last()

            val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
            val service = lifecycle.get()
            val shadow = shadowOf(service as Service) as QueuedStartShadowService
            shadow.rejectedStopSelfIds += 1
            service.onStartCommand(startIntent(service, staleToken), 0, 1)

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

            service.onStartCommand(startIntent(service, liveToken), 0, 2)
            val micType =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            assertEquals(2, shadow.publishedNotifications.size)
            assertEquals(NotificationStreamForegroundService.VOICE_NOTIFICATION_ID, shadow.publishedNotifications[1].id)
            assertEquals(micType, shadow.publishedNotifications[1].serviceType)
            assertFalse(shadow.isForegroundStopped)
            assertEquals(
                listOf(NotificationStreamForegroundService.VOICE_NOTIFICATION_ID),
                service.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id },
            )

            recorder.cancel()
            runCurrent()
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()

            assertStoppedCleanly(service, shadow, expectedStartId = 2)
            lifecycle.destroy()
            fixture.release()
        }

    /** A take ending while a drawer STOP or DISCARD action is queued downgrades the card and stops on arrival. */
    @Test
    fun queuedVoiceStopAndDiscardAfterTakeEndsDowngradesCardAndStopsCleanly() =
        runTest {
            listOf(
                VoiceNoteForegroundService.ACTION_STOP,
                VoiceNoteForegroundService.ACTION_DISCARD,
            ).forEach { action ->
                assertQueuedVoiceActionStopsAfterDowngrade(action)
            }
        }

    /** Verifies a rejected take teardown downgrades the mic card and stops when queued [action] arrives idle. */
    private fun TestScope.assertQueuedVoiceActionStopsAfterDowngrade(action: String) {
        val fixture = Fixture(this)
        VoiceNoteForegroundService.ownerResolver = { fixture.owner }
        val recorder = fixture.owner.bind(chat) { true }
        assertTrue(recorder.start())
        runCurrent()
        val token = fixture.startedTokens.single()

        val lifecycle = Robolectric.buildService(NotificationStreamForegroundService::class.java).create()
        val service = lifecycle.get()
        val shadow = shadowOf(service as Service) as QueuedStartShadowService
        shadow.rejectedStopSelfIds += 1
        service.onStartCommand(startIntent(service, token), 0, 1)

        val micType =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val liveCard = shadow.publishedNotifications.single()
        assertEquals(NotificationStreamForegroundService.VOICE_NOTIFICATION_ID, liveCard.id)
        assertEquals(micType, liveCard.serviceType)
        val liveActions = liveCard.notification.actions
        assertEquals(2, liveActions.orEmpty().size)

        recorder.cancel()
        runCurrent()
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, shadow.publishedNotifications.size)
        val placeholder = shadow.publishedNotifications[1]
        assertEquals(BackgroundConnectionNotification.NOTIFICATION_ID, placeholder.id)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, placeholder.serviceType)
        assertTrue(placeholder.notification.actions.isNullOrEmpty())
        assertTrue(shadow.stopForegroundCalls.isEmpty())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
        assertEquals(
            listOf(BackgroundConnectionNotification.NOTIFICATION_ID),
            service.getSystemService(NotificationManager::class.java).activeNotifications.map { it.id },
        )

        service.onStartCommand(actionIntent(service, action, token), 0, 2)

        assertEquals(2, shadow.publishedNotifications.size)
        assertEquals(
            listOf(
                "startForeground:$micType",
                "stopSelfResult:1:false",
                "startForeground:${ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE}",
                "stopSelfResult:2:true",
                "stopForeground:true",
            ),
            shadow.events,
        )
        assertStoppedCleanly(service, shadow, expectedStartId = 2)
        lifecycle.destroy()
        fixture.release()
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

    /** Builds the voice-note ACTION_START intent carrying the queued session token. */
    private fun startIntent(
        service: NotificationStreamForegroundService,
        token: String,
    ): Intent = actionIntent(service, VoiceNoteForegroundService.ACTION_START, token)

    /** Builds a voice-note service command intent with [action] and [token]. */
    private fun actionIntent(
        service: NotificationStreamForegroundService,
        action: String,
        token: String,
    ): Intent =
        Intent(service, NotificationStreamForegroundService::class.java)
            .setAction(action)
            .putExtra(VoiceNoteForegroundService.EXTRA_TOKEN, token)

    /** Real VoiceNoteSessionOwner wired to a deterministic FakeSession and token recorder. */
    private inner class Fixture(
        scope: TestScope,
        root: File = folder.root,
    ) {
        val startedTokens = mutableListOf<String>()
        private val coordinator = MicrophoneCaptureCoordinator()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val controllers = mutableListOf<VoiceRecordingController>()
        val owner =
            VoiceNoteSessionOwner(
                store = VoiceDraftStore(root),
                newController = { _, hooks ->
                    VoiceRecordingController(
                        context = ApplicationProvider.getApplicationContext<Context>(),
                        outputDirectory = hooks.outputDirectory,
                        scope = scope,
                        onPermissionRequest = hooks.onPermissionRequest,
                        onRecordingComplete = hooks.onRecordingComplete,
                        onError = hooks.onError,
                        microphoneCaptures = coordinator,
                        recorderFactory = { _, file, _ -> FakeSession(file) },
                        mainDispatcher = dispatcher,
                        recorderDispatcher = StandardTestDispatcher(scope.testScheduler, name = "voice-io"),
                        elapsedRealtime = { scope.testScheduler.currentTime },
                        onAutomaticStop = hooks.onAutomaticStop,
                        onRecordingStarted = hooks.onRecordingStarted,
                    ).also(controllers::add)
                },
                presentError = {},
                presentElsewhere = {},
                startForeground = { token ->
                    startedTokens += token
                    true
                },
            )

        /** Releases every controller created during the test. */
        fun release() = controllers.forEach { it.release() }
    }

    /** In-memory recorder session that writes a synthetic file without touching hardware audio. */
    private class FakeSession(
        private val file: File,
    ) : VoiceRecordingSession {
        private var stopped = false

        /** Writes placeholder audio so a later stop has a recording to return. */
        override fun start() = file.writeText("captured audio")

        /** Finishes the take and returns the recorded file with a fixed duration. */
        override fun stop(): VoiceRecordingResult {
            stopped = true
            return VoiceRecordingResult(file, 2_000L)
        }

        /** Discards the recording unless the take was already stopped. */
        override fun cancel() {
            if (!stopped) file.delete()
        }
    }

    private companion object {
        val defaultOwnerResolver = VoiceNoteForegroundService.ownerResolver
        val defaultPublisher = NotificationStreamForegroundService.foregroundPublisher
        val defaultRemover = NotificationStreamForegroundService.foregroundRemover
    }
}
