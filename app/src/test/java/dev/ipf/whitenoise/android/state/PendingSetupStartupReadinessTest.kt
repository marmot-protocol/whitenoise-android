package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.ui.onboarding.setup.SETUP_TEST_ACCOUNT
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

/** Covers the startup barrier when persisted setup diverts normal account activation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingSetupStartupReadinessTest {
    /** Resuming a pending account must refresh privacy before publishing the authenticated shell. */
    @Test
    fun completedSetupRestoresPrivacyBarrierBeforeReady() =
        runBlocking {
            val snapshot = AtomicReference(setupSnapshot())
            val phaseAtPrivacyRead = AtomicReference<AppPhase>()
            lateinit var fixture: NotificationBootstrapTestFixture
            fixture =
                NotificationBootstrapTestFixture(
                    context = RuntimeEnvironment.getApplication(),
                    accounts =
                        listOf(
                            AccountSummaryFfi(SETUP_TEST_ACCOUNT, SETUP_TEST_ACCOUNT, true, false, false, false),
                        ),
                    emitStartupNotification = false,
                    onOnboardingSnapshot = { snapshot.get() },
                    onAuditLogSettings = { phaseAtPrivacyRead.set(fixture.appState.phase) },
                )
            try {
                fixture.bootstrap()
                assertEquals(AppPhase.Onboarding, fixture.appState.phase)
                val controller = requireNotNull(fixture.appState.accountSetup.controller)
                fixture.runWithMainLooperPumping {
                    withTimeout(5_000L) {
                        while (controller.state.value.busy) delay(1L)
                        snapshot.set(setupSnapshot(ready = true))
                        controller.openChats()
                        while (fixture.appState.phase != AppPhase.Ready) delay(1L)
                    }
                }
                assertEquals(AppPhase.Onboarding, phaseAtPrivacyRead.get())
                assertTrue(fixture.subscriptionCalls.get() > 0)
                assertEquals(SETUP_TEST_ACCOUNT, fixture.appState.activeAccountRef)
            } finally {
                fixture.close()
            }
        }
}
