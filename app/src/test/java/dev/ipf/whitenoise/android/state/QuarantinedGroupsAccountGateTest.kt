package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QuarantinedGroupsAccountGateTest {
    @Test fun factoryRequiresDeveloperModeAndASignedInSigningAccount() {
        val state = state()
        state.updateDeveloperMode(false)
        assertNull(state.quarantinedGroupsAccess())
        state.updateDeveloperMode(true)
        assertNotNull(state.quarantinedGroupsAccess())
        assertNull(state(signedOut = true).also { it.updateDeveloperMode(true) }.quarantinedGroupsAccess())
        assertNull(state(signing = false).also { it.updateDeveloperMode(true) }.quarantinedGroupsAccess())
        assertNull(state(runtime = false).also { it.updateDeveloperMode(true) }.quarantinedGroupsAccess())
    }

    @Test fun teardownRetiresCapturedAccess() =
        runTest {
            val state = state().also { it.updateDeveloperMode(true) }
            val old = state.quarantinedGroupsAccess()!!
            state.signOutInProgress = true
            assertFalse(old.isCurrent())
            assertNull(state.quarantinedGroupsAccess())
            state.signOutInProgress = false
            state.wipeInProgress = true
            assertFalse(old.isCurrent())
            assertNull(state.quarantinedGroupsAccess())
            state.wipeInProgress = false
            assertTrue(old.isCurrent())
            val fresh = state.quarantinedGroupsAccess()!!
            assertTrue(fresh.isCurrent())
            assertTrue(fresh.load().isEmpty())
        }

    private fun state(
        signedOut: Boolean = false,
        signing: Boolean = true,
        runtime: Boolean = true,
    ): WhiteNoiseAppState {
        val native =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "quarantinedGroups" -> emptyList<Any>()
                    "toString" -> "QuarantinedAccountGateFake"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("unexpected native call")
                }
            } as MarmotInterface
        return WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        ConversationTimelineTestIds.ACCOUNT_REF,
                        ConversationTimelineTestIds.ACCOUNT_ID,
                        signing,
                        false,
                        signedOut,
                        true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            initialMarmotRuntime = if (runtime) AppMarmotRuntime("private", native) else null,
        )
    }
}
