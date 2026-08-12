package dev.ipf.whitenoise.android.state

import android.app.Application
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStartupOrderingTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun coldBootstrapAttachesReceiverBeforeFirstPostStartFfiWork() =
        runBlocking {
            val fixture = NotificationBootstrapTestFixture(context)
            try {
                fixture.appState.bootstrap()
                fixture.awaitUpdateConsumed()

                assertTrue(fixture.receiverWasAttachedAtPostStartEmission)
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun subscriptionFailureIsBoundedAndTheSameRuntimeCanRecover() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyFailSubscriptions = true,
                    receiverTimeoutMillis = 25L,
                )
            try {
                fixture.appState.bootstrap()

                assertTrue(fixture.appState.phase is AppPhase.Failed)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertTrue(fixture.subscriptionCalls.get() >= 1)

                fixture.allowSubscriptions()
                fixture.appState.bootstrap()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun cancelledBootstrapLeavesTheSharedListenerAndRuntimeReusable() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockSubscriptions = true,
                    receiverTimeoutMillis = 60_000L,
                )
            try {
                val bootstrap =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.appState.bootstrap()
                    }

                while (fixture.subscriptionCalls.get() == 0) kotlinx.coroutines.yield()
                bootstrap.cancelAndJoin()

                assertTrue(fixture.appState.phase is AppPhase.Failed)
                assertEquals(1, fixture.runtimeStartCalls.get())

                fixture.allowSubscriptions()
                fixture.appState.bootstrap()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun repeatedStartupCallsReuseOneRuntimeAndReceiver() =
        runBlocking {
            val fixture = NotificationBootstrapTestFixture(context)
            try {
                fixture.appState.bootstrap()
                fixture.appState.bootstrap()
                fixture.appState.ensureNotificationRuntimeStarted()

                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
            } finally {
                fixture.close()
            }
        }
}
