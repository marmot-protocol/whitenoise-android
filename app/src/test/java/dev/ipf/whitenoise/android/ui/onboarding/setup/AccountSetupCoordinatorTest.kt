package dev.ipf.whitenoise.android.ui.onboarding.setup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
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

    /** Supplies deterministic native outcomes for every setup eligibility branch. */
    private fun setupMarmot(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            val accountRef = arguments?.firstOrNull() as? String
            when (method.name) {
                "onboardingRecoveryRequired" ->
                    when (accountRef) {
                        "recovery" -> true
                        "broken-recovery" -> error("unreadable recovery marker")
                        else -> false
                    }
                "onboardingSnapshot" ->
                    when (accountRef) {
                        "pending" -> setupSnapshot()
                        "broken-snapshot" -> error("unreadable checkpoint")
                        else -> null
                    }
                "toString" -> "AccountSetupCoordinatorMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

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
