package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Exercises the actual Amber sign-in failure boundary with an isolated native runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmberSignInBoundaryTest {
    /** A staged duplicate keeps the imported nsec account and its data in place. */
    @Test
    fun stagedDuplicatePreservesExistingAccount() = assertDuplicateBoundary(legacyFallback = false)

    /** A legacy-login duplicate has the same account-preserving failure behavior. */
    @Test
    fun legacyDuplicatePreservesExistingAccount() = assertDuplicateBoundary(legacyFallback = true)

    /** A signer rejection remains a gentle cancellation instead of a failure report. */
    @Test
    fun signerCancellationRetainsTransientNotice() =
        runBlocking {
            val fixture = fixture(legacyFallback = false)
            fixture.state.amberPublicKeyRequest = { throw MarmotKitException.ExternalSignerRejected() }

            fixture.state.loginWithAmber()

            assertEquals(AppText.Resource(R.string.toast_amber_sign_in_cancelled), fixture.state.transientNotice?.title)
            assertNull(fixture.state.toast)
            assertNull(fixture.state.amberSignInStage)
            assertTrue(fixture.calls.isEmpty())
        }

    /** Unrelated failures retain the generic retry detail and privacy-safe report. */
    @Test
    fun unrelatedFailureRetainsGenericPresentation() =
        runBlocking {
            val fixture = fixture(legacyFallback = false)
            fixture.state.amberPublicKeyRequest = {
                throw MarmotKitException.ExternalSignerUnavailable("private account")
            }

            fixture.state.loginWithAmber()

            assertEquals(AppText.Resource(R.string.error_try_again), fixture.state.toast?.detail)
            assertTrue(fixture.state.toast?.copyable == true)
            assertEquals(null, fixture.state.transientNotice)
            assertNull(fixture.state.amberSignInStage)
            assertTrue(fixture.calls.isEmpty())
        }

    /** Verifies both failure paths never reach any account or conversation mutation. */
    private fun assertDuplicateBoundary(legacyFallback: Boolean) =
        runBlocking {
            val fixture = fixture(legacyFallback)

            fixture.state.loginWithAmber()

            val expectedCalls =
                if (legacyFallback) {
                    listOf("accountIdHex", "beginExternalSignerOnboarding", "loginExternalSigner")
                } else {
                    listOf("accountIdHex", "beginExternalSignerOnboarding")
                }
            assertEquals(expectedCalls, fixture.calls.toList())
            assertEquals("existing", fixture.state.activeAccountRef)
            assertEquals(listOf(fixture.existingAccount), fixture.state.accounts)
            val existingAccount = fixture.state.accounts.single()
            assertTrue(existingAccount.localSigning)
            assertFalse(existingAccount.externalSigning)
            val toast = fixture.state.toast
            assertEquals(AppText.Resource(R.string.amber_identity_already_added), toast?.detail)
            val report = toast?.diagnosticReport.orEmpty()
            assertTrue(report.contains("error=ALREADY_EXISTS"))
            assertTrue(report.contains("marmot=DuplicateIdentity"))
            assertNull(fixture.state.amberSignInStage)
        }

    /** Fakes only the pre-commit MDK calls; any data mutation fails the test. */
    private fun fixture(legacyFallback: Boolean): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account = AccountSummaryFfi("existing", ACCOUNT_ID, true, false, false, true)
        val calls = ConcurrentLinkedQueue<String>()
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "accountIdHex" -> {
                        calls.add(method.name)
                        ACCOUNT_ID
                    }
                    "beginExternalSignerOnboarding" -> {
                        calls.add(method.name)
                        val failure =
                            if (legacyFallback) {
                                MarmotKitException.OnboardingActionUnavailable()
                            } else {
                                MarmotKitException.DuplicateIdentity("private account")
                            }
                        failSuspendedCall(arguments, failure)
                    }
                    "loginExternalSigner" -> {
                        calls.add(method.name)
                        failSuspendedCall(arguments, MarmotKitException.DuplicateIdentity("private account"))
                    }
                    "toString" -> "AmberSignInMarmotFake"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected Marmot call: ${method.name}")
                }
            } as MarmotInterface
        val runtime = AppMarmotRuntime(rootPath = "test", marmot = marmot)
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = listOf(account),
                activeAccountRef = account.label,
                initialMarmotRuntime = runtime,
                marmotRuntimeFactory = { runtime },
            )
        state.amberPublicKeyRequest = { ACCOUNT_ID }
        return Fixture(state, account, calls)
    }

    /** Delivers a checked UniFFI error directly through the suspend continuation. */
    private fun failSuspendedCall(
        arguments: Array<out Any?>?,
        failure: MarmotKitException,
    ): Any {
        @Suppress("UNCHECKED_CAST")
        val continuation = arguments?.last() as Continuation<Any?>
        continuation.resumeWith(Result.failure(failure))
        return COROUTINE_SUSPENDED
    }

    private data class Fixture(
        val state: WhiteNoiseAppState,
        val existingAccount: AccountSummaryFfi,
        val calls: ConcurrentLinkedQueue<String>,
    )

    private companion object {
        const val ACCOUNT_ID = "aa000000000000000000000000000000000000000000000000000000000000aa"
    }
}
