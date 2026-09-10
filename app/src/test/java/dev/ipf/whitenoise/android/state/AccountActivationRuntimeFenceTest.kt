package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises deferred activation against replacement of the same account's runtime owner. */
@RunWith(RobolectricTestRunner::class)
class AccountActivationRuntimeFenceTest {
    /** Waiting for a readable frame cannot admit old activation work into a replacement runtime. */
    @Test
    fun runtimeReplacementDuringFirstFrameWaitSkipsDeferredRefreshes() =
        runBlocking {
            val notificationReads = AtomicInteger()
            val fixture = fixture(notificationReads)
            val release = CompletableDeferred<Unit>()
            try {
                fixture.bootstrap()
                val baseline = notificationReads.get()
                val entered = CompletableDeferred<Unit>()
                val activation =
                    async {
                        fixture.appState.setActiveAccount(
                            "self",
                            awaitPostActivationWork = {
                                entered.complete(Unit)
                                release.await()
                            },
                        )
                    }
                withTimeout(3_000) { entered.await() }
                replaceRuntimeOwner(fixture.appState)
                release.complete(Unit)
                assertTrue(activation.await())
                assertEquals(baseline, notificationReads.get())
                assertTrue(fixture.appState.setActiveAccount("self"))
                assertTrue("a fresh activation still performs the refresh", notificationReads.get() > baseline)
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    /** A step already in flight may finish, but its replacement owner rejects subsequent steps. */
    @Test
    fun runtimeReplacementDuringPrivacyRefreshSkipsSubsequentRefreshes() =
        runBlocking {
            val notificationReads = AtomicInteger()
            val holdPrivacy = AtomicBoolean(false)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fixture =
                fixture(notificationReads) {
                    if (holdPrivacy.get()) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            try {
                fixture.bootstrap()
                val baseline = notificationReads.get()
                holdPrivacy.set(true)
                val activation = async { fixture.appState.setActiveAccount("self") }
                withTimeout(3_000) { while (entered.count > 0) yield() }
                replaceRuntimeOwner(fixture.appState)
                release.countDown()
                assertTrue(activation.await())
                assertEquals(baseline, notificationReads.get())
            } finally {
                release.countDown()
                fixture.close()
            }
        }

    /** Existing FFI hooks count observable settings reads without introducing production test hooks. */
    private fun fixture(
        notificationReads: AtomicInteger,
        onPrivacyRuntimeConfig: (() -> Unit)? = null,
    ) =
        NotificationBootstrapTestFixture(
            context = RuntimeEnvironment.getApplication(),
            accounts = listOf(AccountSummaryFfi("self", "11".repeat(32), true, false, false, true)),
            emitStartupNotification = false,
            onPrivacyRuntimeConfig = onPrivacyRuntimeConfig,
            onNotificationSettings = { accountRef ->
                notificationReads.incrementAndGet()
                NotificationSettingsFfi(accountRef, "11".repeat(32), true, false)
            },
        )

    /** Replays the existing recovered-runtime publication boundary without wiping any account. */
    private fun replaceRuntimeOwner(appState: WhiteNoiseAppState) {
        val state =
            DestructiveAccountWipeRuntimeState(
                activeAccountRef = appState.activeAccountRef,
                activeConversationAccountRef = null,
                activeConversationGroupIdHex = null,
                runtimeGeneration = appState.runtimeGeneration + 1,
            )
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("applyDestructiveWipeRuntimeState", DestructiveAccountWipeRuntimeState::class.java)
            .apply { isAccessible = true }
            .invoke(appState, state)
    }
}
