package dev.ipf.whitenoise.android.ui.onboarding.setup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Covers account-isolated setup eligibility reads at the startup boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountSetupCoordinatorTest {
    /** One unreadable checkpoint fails closed without hiding valid states for sibling accounts. */
    @Test
    fun setupReadFailuresStayAccountScopedAndFailClosed() =
        runBlocking {
            val accounts = listOf("recovery", "pending", "broken-recovery", "broken-snapshot", "ready").map(::account)
            val appState = appState(accounts, setupMarmot())

            val state = appState.accountSetup.accountsState(accounts)
            appState.accountSetup.acceptAccounts(state)

            assertEquals(setOf("recovery"), state.recoveryRequired)
            assertEquals(setOf("pending", "broken-recovery", "broken-snapshot"), state.pending)
            assertTrue(appState.accountSetup.needsRecovery("recovery"))
            assertFalse(appState.accountSetup.eligible(account("broken-recovery")))
            assertFalse(appState.accountSetup.eligible(account("broken-snapshot")))
            assertTrue(appState.accountSetup.eligible(account("ready")))
        }

    /** A transient startup failure cannot bypass a recovery marker discovered at activation time. */
    @Test
    fun activationRechecksRecoveryAfterStartupReadFailure() =
        runBlocking {
            val account = account("flaky-recovery")
            var recoveryReads = 0
            val appState =
                appState(
                    listOf(account),
                    setupMarmot(
                        recoveryRequired = {
                            recoveryReads += 1
                            if (recoveryReads == 1) error("transient recovery read failure")
                            true
                        },
                        onboardingSnapshot = { error("recovery-required accounts must not read a snapshot") },
                    ),
                )

            val state = appState.accountSetup.accountsState(listOf(account))
            appState.accountSetup.acceptAccounts(state)

            assertFalse(appState.accountSetup.needsRecovery(account.label))
            assertTrue(appState.accountSetup.routeIfPending(account.label))
            assertTrue(appState.accountSetup.needsRecovery(account.label))
            assertFalse(appState.accountSetup.eligible(account))
            assertEquals(2, recoveryReads)
        }

    /** A repeated recovery-status failure keeps ordinary account activation blocked. */
    @Test
    fun activationFailsClosedWhileRecoveryReadIsUnavailable() =
        runBlocking {
            val account = account("unreadable-recovery")
            val appState =
                appState(
                    listOf(account),
                    setupMarmot(
                        recoveryRequired = { error("unreadable recovery marker") },
                        onboardingSnapshot = { error("failed recovery reads must not fall through") },
                    ),
                )

            val state = appState.accountSetup.accountsState(listOf(account))
            appState.accountSetup.acceptAccounts(state)

            assertTrue(appState.accountSetup.routeIfPending(account.label))
            assertFalse(appState.accountSetup.needsRecovery(account.label))
            assertFalse(appState.accountSetup.eligible(account))
        }

    /** Supplies deterministic native outcomes for every setup eligibility branch. */
    private fun setupMarmot(
        recoveryRequired: (String?) -> Boolean = ::recoveryRequired,
        onboardingSnapshot: (String?) -> OnboardingSnapshotFfi? = ::onboardingSnapshot,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            val accountRef = arguments?.firstOrNull() as? String
            when (method.name) {
                "onboardingRecoveryRequired" -> recoveryRequired(accountRef)
                "onboardingSnapshot" -> onboardingSnapshot(accountRef)
                "toString" -> "AccountSetupCoordinatorMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** Returns the default recovery fixture outcome for an account label. */
    private fun recoveryRequired(accountRef: String?): Boolean =
        when (accountRef) {
            "recovery" -> true
            "broken-recovery" -> error("unreadable recovery marker")
            else -> false
        }

    /** Returns the default onboarding snapshot fixture outcome for an account label. */
    private fun onboardingSnapshot(accountRef: String?): OnboardingSnapshotFfi? =
        when (accountRef) {
            "pending" -> setupSnapshot()
            "broken-snapshot" -> error("unreadable checkpoint")
            else -> null
        }

    /** Attaches the fake runtime without starting Android platform services. */
    private fun appState(
        accounts: List<AccountSummaryFfi>,
        marmot: MarmotInterface,
    ): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts = accounts,
            activeAccountRef = "ready",
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }
    }

    /** Creates one local signer account with a stable synthetic identity. */
    private fun account(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = label.padEnd(64, 'a').take(64),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
