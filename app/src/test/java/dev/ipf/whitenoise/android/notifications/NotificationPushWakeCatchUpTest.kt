package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Looper
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

/** Exercises real AppState catch-up, durable wake storage, and notification posting without an Activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPushWakeCatchUpTest {
    /** A warm background process must recover from a relay failure before releasing its wake. */
    @Test
    fun warmWakeRetriesFailedFetchAndPostsMessageAndReaction() = runBlocking { assertTransientRecovery(warm = true) }

    /** The same retry contract applies when FCM is the first caller to bootstrap the process. */
    @Test
    fun coldWakeRetriesFailedFetchAndPostsMessageAndReaction() = runBlocking { assertTransientRecovery(warm = false) }

    /** A successful decoy or muted wake needs no visible update and must not trigger retries. */
    @Test
    fun successfulQuietCatchUpCompletesOnceWithoutNotification() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            var fetches = 0
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    onCatchUpAccounts = { fetches++ },
                    emitStartupNotification = false,
                )
            try {
                store.recordPendingPushWakeCatchUp()
                var drained: Boolean? = null
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { drained = fixture.awaitPushDrain(100L) },
                    )

                assertEquals(NotificationRuntimeSupervisionOutcome.Started(1), outcome)
                assertEquals(1, fetches)
                assertEquals(false, drained)
                assertFalse(store.pushWakeCatchUpPending())
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Offline catch-up exhausts the existing retry budget and retains the wake for later recovery. */
    @Test
    fun repeatedFetchFailureRetainsDurableWakeAfterFourAttempts() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            var fetches = 0
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    onCatchUpAccounts = {
                        fetches++
                        throw IOException("relay unavailable")
                    },
                    emitStartupNotification = false,
                )
            try {
                store.recordPendingPushWakeCatchUp()
                val generation = store.pendingPushWakeCatchUpGeneration()
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { fixture.awaitPushDrain(100L) },
                    )

                assertTrue(outcome is NotificationRuntimeSupervisionOutcome.Exhausted)
                assertEquals(4, fetches)
                assertEquals(generation, store.pendingPushWakeCatchUpGeneration())
                assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Successful work for an older wake must leave a concurrently received wake pending. */
    @Test
    fun newerWakeSurvivesSuccessfulCatchUp() =
        runBlocking {
            val context: Application = RuntimeEnvironment.getApplication()
            val store = PushTokenStore.create(context)
            val fixture =
                NotificationBootstrapTestFixture(
                    context,
                    onCatchUpAccounts = { store.recordPendingPushWakeCatchUp() },
                    emitStartupNotification = false,
                )
            try {
                store.recordPendingPushWakeCatchUp()
                val generation = store.pendingPushWakeCatchUpGeneration()
                val outcome =
                    supervisor().supervise(
                        recoveryAllowed = { true },
                        startRuntime = { fixture.awaitPushDrain(100L) },
                    )

                assertEquals(NotificationRuntimeSupervisionOutcome.Started(1), outcome)
                assertTrue(store.pendingPushWakeCatchUpGeneration() > generation)
            } finally {
                store.clearPendingPushWakeCatchUp()
                fixture.close()
            }
        }

    /** Injects failure at the native fetch boundary, then verifies both Android notification types. */
    private suspend fun assertTransientRecovery(warm: Boolean) {
        val context: Application = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        val store = PushTokenStore.create(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var fetches = 0
        lateinit var fixture: NotificationBootstrapTestFixture
        fixture =
            NotificationBootstrapTestFixture(
                context,
                onCatchUpAccounts = {
                    fetches++
                    if (fetches == 1) throw IOException("transient relay failure")
                    fixture.emitNotification()
                    fixture.emitNotification(
                        fixture.update.copy(
                            notificationKey = "reaction:account-a:message-a",
                            reactionEmoji = "👍",
                            reactedToPreview = "Original message",
                        ),
                    )
                },
                emitStartupNotification = false,
            )
        try {
            if (warm) fixture.bootstrap()
            store.recordPendingPushWakeCatchUp()
            val outcome =
                supervisor().supervise(
                    recoveryAllowed = { true },
                    startRuntime = { fixture.awaitPushDrain(1_000L) },
                )

            assertEquals(NotificationRuntimeSupervisionOutcome.Started(2), outcome)
            assertEquals(2, fetches)
            assertFalse(store.pushWakeCatchUpPending())
            withTimeout(5_000L) {
                while (manager.activeNotifications.map { it.id }.toSet() != setOf(0, 1)) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
            }
        } finally {
            manager.cancelAll()
            store.clearPendingPushWakeCatchUp()
            fixture.close()
        }
    }

    /** Keeps production retry limits while removing wall-clock backoff from the regression. */
    private fun supervisor() = NotificationRuntimeSupervisor(waitBeforeRetry = {})
}
