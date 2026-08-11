package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationStartupOrderingTest {
    @Test
    fun coldBootstrapCreatesChannelsThenAwaitsReceiverAtTheFirstPostStartBoundary() {
        val bootstrap = appStateSource().readText().functionBody("bootstrapLocked")
        val ensureChannels = bootstrap.indexOf("localNotificationPresenter.ensureChannels()")
        val startRuntime = bootstrap.indexOf("opened.marmot.start()")
        val launchReceiver = bootstrap.indexOf("startNotificationListener(CoroutineStart.UNDISPATCHED)")
        val awaitReceiver = bootstrap.indexOf("if (!awaitNotificationReceiverForStartup())")
        val startupLog = bootstrap.indexOf("marmot started; notification receiver active")
        val refreshPrivacy = bootstrap.indexOf("refreshSecurityPrivacySettings()")
        val refreshAccounts = bootstrap.indexOf("refreshAccountsForBootstrap()")

        assertTrue(
            "notification channels must exist before runtime updates can arrive",
            ensureChannels in 0 until startRuntime,
        )
        assertTrue("receiver must launch on the runtime IO boundary", startRuntime in 0 until launchReceiver)
        assertTrue("receiver readiness must gate remaining startup", launchReceiver in 0 until awaitReceiver)
        assertTrue("even startup logging must wait for receiver readiness", awaitReceiver in 0 until startupLog)
        assertTrue("privacy refresh must wait for the receiver", awaitReceiver in 0 until refreshPrivacy)
        assertTrue("account startup work must wait for the receiver", awaitReceiver in 0 until refreshAccounts)
        assertFalse(
            "cold bootstrap must not fire-and-forget receiver attachment",
            "startNotificationListener()" in bootstrap,
        )
    }

    @Test
    fun bootstrapCancellationCannotLeaveTheSplashPhaseLatched() {
        val bootstrap = appStateSource().readText().functionBody("bootstrapLocked")
        val cancellationBranch =
            bootstrap
                .substringAfter("if (error is CancellationException)")
                .substringBefore("appStateDebug(error)")

        assertTrue(
            "cancellation must leave Bootstrapping before it propagates",
            "phase = AppPhase.Failed" in cancellationBranch,
        )
        assertTrue("cancellation must still propagate to its lifecycle owner", "throw error" in cancellationBranch)
    }

    @Test
    fun cancelledColdBootstrapResumesWithoutStartingAnotherNativeRuntime() {
        val source = appStateSource().readText()
        val bootstrap = source.functionBody("bootstrapLocked")
        val runtimeStart = bootstrap.indexOf("opened.marmot.start()")
        val markStarted = bootstrap.indexOf("marmotStarted = true")
        val markComplete = bootstrap.indexOf("bootstrapCompleted = true")
        val ensureRuntime = source.functionBody("ensureNotificationRuntimeStarted")

        assertTrue("start must be guarded for a resumed bootstrap", "if (!marmotStarted)" in bootstrap)
        assertTrue("native completion must publish immediately after start", runtimeStart in 0 until markStarted)
        assertTrue("bootstrap completion must wait for all startup reconciliation", markStarted in 0 until markComplete)
        assertTrue("runtime callers must resume incomplete bootstrap", "if (!bootstrapCompleted)" in ensureRuntime)
    }

    @Test
    fun noReplayBroadcastDropsUpdateEmittedBeforeStartupAttachesReceiver() =
        runTest {
            val broadcast = NoReplayNotificationBroadcastFake(this)

            broadcast.emit("startup-message")
            assertTrue(broadcast.postedNotifications.isEmpty())

            assertTrue(broadcast.establishReceiver())
            broadcast.emit("attached-message")

            assertEquals(listOf("attached-message"), broadcast.postedNotifications)
            broadcast.close()
        }

    @Test
    fun startupReadinessPrecedesCatchUpEmission() =
        runTest {
            val broadcast = NoReplayNotificationBroadcastFake(this)

            val receiverReady = broadcast.establishReceiver()
            if (receiverReady) broadcast.emit("startup-catch-up-message")

            assertTrue(receiverReady)
            assertEquals(listOf("startup-catch-up-message"), broadcast.postedNotifications)
            broadcast.close()
        }

    @Test
    fun subscriptionFailureIsBoundedAndLeavesTheSharedListenerRetrying() =
        runTest {
            val broadcast = NoReplayNotificationBroadcastFake(this, attachReceiver = false)

            assertFalse(broadcast.establishReceiver(timeoutMillis = 1_000L))

            assertEquals(1_000L, testScheduler.currentTime)
            assertTrue("the process-owned retry loop must survive a bounded startup wait", broadcast.listenerIsActive())
            broadcast.close()
        }

    @Test
    fun cancelledBootstrapWaitDoesNotCancelTheSharedListener() =
        runTest {
            val broadcast = NoReplayNotificationBroadcastFake(this, attachReceiver = false)
            val startup =
                async(start = CoroutineStart.UNDISPATCHED) {
                    broadcast.establishReceiver(timeoutMillis = 60_000L)
                }

            runCurrent()
            assertFalse(startup.isCompleted)
            startup.cancelAndJoin()

            assertTrue(
                "bootstrap cancellation must not take process-owned listener retries with it",
                broadcast.listenerIsActive(),
            )
            broadcast.close()
        }

    @Test
    fun repeatedStartupCallsReuseOneListener() =
        runTest {
            val broadcast = NoReplayNotificationBroadcastFake(this)

            assertTrue(broadcast.establishReceiver())
            assertTrue(broadcast.establishReceiver())

            assertEquals(1, broadcast.listenerStarts)
            broadcast.close()
        }

    private fun appStateSource() =
        listOf(
            java.io.File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            java.io.File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() } ?: error("Missing AppState.kt source file")

    /** Models MDK's passive Tokio broadcast: an update has no replay for later receivers. */
    private class NoReplayNotificationBroadcastFake(
        private val scope: CoroutineScope,
        private val attachReceiver: Boolean = true,
    ) {
        private val slot = NotificationJobSlot()
        private val receiverActive = MutableStateFlow(false)
        private val retryWake = MutableStateFlow(0L)

        val postedNotifications = mutableListOf<String>()
        var listenerStarts = 0
            private set

        suspend fun establishReceiver(timeoutMillis: Long = 5_000L): Boolean =
            awaitNotificationReceiverForStartup(
                notificationJob = slot,
                receiverActive = receiverActive,
                receiverRetryWake = retryWake,
                timeoutMillis = timeoutMillis,
                launchListener = ::launchListener,
            )

        fun emit(message: String) {
            if (receiverActive.value) postedNotifications += message
        }

        fun listenerIsActive(): Boolean = slot.isActive()

        suspend fun close() {
            slot.cancelAndJoin()
        }

        private fun launchListener(): Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                listenerStarts += 1
                if (attachReceiver) receiverActive.value = true
                try {
                    awaitCancellation()
                } finally {
                    receiverActive.value = false
                }
            }
    }
}
