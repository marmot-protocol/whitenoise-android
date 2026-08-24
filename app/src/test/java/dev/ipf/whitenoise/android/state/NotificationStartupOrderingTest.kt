package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
                fixture.bootstrap()
                fixture.awaitUpdateConsumed()

                assertTrue(fixture.receiverWasAttachedAtPostStartEmission)
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun coldIdentityFallbackPostsWithoutCanonicalNpubFfiWork() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()

                assertEquals(
                    "the first complete card must use cached or deterministic identity text",
                    0,
                    fixture.npubCalls.get(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun coldBootstrapHasNoPostStartListenerDispatchGap() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    delayFirstNotificationDispatchAfterRuntimeStart = true,
                )
            try {
                fixture.bootstrap()

                assertTrue(
                    "the first subscription attempt must begin without a post-start dispatcher hop",
                    fixture.receiverWasAttachedAtPostStartEmission,
                )
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
                fixture.bootstrap()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertTrue(fixture.subscriptionCalls.get() >= 1)

                fixture.allowSubscriptions(recoveryTimeoutMillis = 2_000L)
                fixture.ensureNotificationRuntimeStarted()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun synchronousSubscriptionSetupCannotEscapeStartupTimeout() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockSubscriptionsSynchronously = true,
                    receiverTimeoutMillis = 25L,
                )
            val bootstrap =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.bootstrap()
                }
            try {
                val completedWithinBound =
                    withTimeoutOrNull(2_000L) {
                        bootstrap.join()
                        true
                    } ?: false

                assertTrue("bootstrap must remain bounded while native subscription setup blocks", completedWithinBound)
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())

                fixture.allowSubscriptions(recoveryTimeoutMillis = 2_000L)
                fixture.ensureNotificationRuntimeStarted()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
            } finally {
                fixture.allowSubscriptions()
                bootstrap.cancelAndJoin()
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
                        fixture.bootstrap()
                    }

                withTimeout(5_000L) {
                    while (fixture.subscriptionCalls.get() == 0) yield()
                }
                bootstrap.cancelAndJoin()

                assertTrue(fixture.appState.phase is AppPhase.Bootstrapping)
                assertEquals(1, fixture.runtimeStartCalls.get())

                fixture.allowSubscriptions()
                fixture.bootstrap()

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
                fixture.bootstrap()
                fixture.bootstrap()
                fixture.ensureNotificationRuntimeStarted()

                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
            } finally {
                fixture.close()
            }
        }
}
