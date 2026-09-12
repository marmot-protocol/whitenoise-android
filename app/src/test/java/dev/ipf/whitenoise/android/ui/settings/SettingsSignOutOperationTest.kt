package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/** The UI sign-out helper must claim teardown synchronously and release it after a retained-account failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class SettingsSignOutOperationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var currentFixture: Fixture? = null

    /** Two same-frame confirmations issue one native call; an unfinished cleanup releases the UI for explicit retry. */
    @Test
    fun repeatedConfirmationRunsOnceAndRetainedAccountCanRetry() {
        val fixture = render()
        composeRule.runOnIdle {
            signOutActiveAccount(fixture.state, deleteKeyPackages = false)
            signOutActiveAccount(fixture.state, deleteKeyPackages = true)
            assertTrue(fixture.state.signOutInProgress)
        }
        awaitPending(fixture)
        assertEquals(listOf(false), fixture.native.deleteKeyPackages)
        composeRule.onNodeWithTag("settings.sign_out_progress").assertExists()
        composeRule.runOnIdle { assertEquals(ACCOUNT_REF, fixture.state.activeAccountRef) }

        fixture.native.finishRetainingAccount()
        awaitIdle(fixture)
        composeRule.onNodeWithTag("settings.sign_out_progress").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(ACCOUNT_REF, fixture.state.activeAccountRef)
            assertEquals(ACCOUNT_REF, fixture.state.activeAccount?.label)
            signOutActiveAccount(fixture.state, deleteKeyPackages = true)
            assertTrue(fixture.state.signOutInProgress)
        }
        awaitPending(fixture)
        assertEquals(listOf(false, true), fixture.native.deleteKeyPackages)
        fixture.native.finishRetainingAccount()
        awaitIdle(fixture)
        assertTrue(fixture.native.unexpectedCalls.isEmpty())
    }

    /** A wipe already owns teardown, so sign-out cannot enter native code or take over its progress state. */
    @Test
    fun activeWipeBlocksSignOutUntilItsGuardIsReleased() {
        val fixture = render()
        composeRule.runOnIdle {
            fixture.state.wipeInProgress = true
            signOutActiveAccount(fixture.state, deleteKeyPackages = true)
            assertFalse(fixture.state.signOutInProgress)
            assertTrue(fixture.state.wipeInProgress)
        }
        composeRule.waitForIdle()
        assertTrue(fixture.native.deleteKeyPackages.isEmpty())
        composeRule.runOnIdle {
            fixture.state.wipeInProgress = false
            signOutActiveAccount(fixture.state, deleteKeyPackages = false)
        }
        awaitPending(fixture)
        assertEquals(listOf(false), fixture.native.deleteKeyPackages)
        fixture.native.finishRetainingAccount()
        awaitIdle(fixture)
        assertTrue(fixture.native.unexpectedCalls.isEmpty())
    }

    /** Releases a held fake call even when an assertion fails, then retires this fixture's mutation scope. */
    @After
    fun releaseFixture() {
        currentFixture?.let { fixture ->
            fixture.native.finishRetainingAccount()
            composeRule.runOnIdle { fixture.state.mutationsScope.cancel() }
        }
    }

    /** Waits for the actual native continuation, rather than treating an unchanged progress flag as proof of a call. */
    private fun awaitPending(fixture: Fixture) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { fixture.native.pending.get() != null }
        composeRule.runOnIdle { assertTrue(fixture.state.signOutInProgress) }
    }

    /** Waits until the real helper's finally block releases teardown after the native result has been returned. */
    private fun awaitIdle(fixture: Fixture) {
        composeRule.waitUntil(TIMEOUT_MILLIS) { !fixture.state.signOutInProgress }
        composeRule.runOnIdle {
            assertFalse(fixture.state.signOutInProgress)
            assertEquals(ACCOUNT_REF, fixture.state.activeAccountRef)
        }
    }

    /** No platform services or real identity is opened; the progress overlay observes the actual helper's state. */
    private fun render(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val native = SuspendedSignOut()
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = listOf(account()),
                activeAccountRef = ACCOUNT_REF,
                initialMarmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = native.marmot),
            )
        val fixture = Fixture(state, native)
        currentFixture = fixture
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    if (state.signOutInProgress) SignOutProgressDialog()
                }
            }
        }
        return fixture
    }

    /** Account metadata is enough for the native retained-session path; no broad teardown fixture is necessary. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = "b".repeat(64),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private data class Fixture(
        val state: WhiteNoiseAppState,
        val native: SuspendedSignOut,
    )

    /** Holds signOut at its real suspend boundary and reports unfinished local cleanup when released. */
    private class SuspendedSignOut {
        val deleteKeyPackages = CopyOnWriteArrayList<Boolean>()
        val unexpectedCalls = CopyOnWriteArrayList<String>()
        val pending = AtomicReference<Continuation<Any?>?>()
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "signOut" -> suspendSignOut(arguments)
                    "toString" -> "SuspendedSignOutTestProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> {
                        unexpectedCalls += method.name
                        error("Unexpected native sign-out call: ${method.name}")
                    }
                }
            } as MarmotInterface

        /** Records the exact cleanup preference and fails immediately if a concurrent call reaches native code. */
        private fun suspendSignOut(arguments: Array<out Any?>?): Any {
            val args = requireNotNull(arguments)
            check(args[0] == ACCOUNT_REF)
            deleteKeyPackages += args[1] as Boolean
            @Suppress("UNCHECKED_CAST")
            val continuation = args.last() as Continuation<Any?>
            check(pending.compareAndSet(null, continuation)) { "Concurrent native sign-out" }
            return COROUTINE_SUSPENDED
        }

        /** MDK's unfinished-local-cleanup outcome keeps the account and avoids all success-path teardown work. */
        fun finishRetainingAccount() {
            pending.getAndSet(null)?.resume(
                SignOutOutcomeFfi(
                    keyPackagesDeleted = 0u,
                    keyPackageFailures = emptyList(),
                    localCleanup = LocalCleanupReportFfi(completed = false, reason = "test worker remains active"),
                ),
            )
        }
    }

    private companion object {
        const val ACCOUNT_REF = "sign-out-operation-test"
        const val TIMEOUT_MILLIS = 5_000L
    }
}
