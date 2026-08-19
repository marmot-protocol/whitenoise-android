package dev.ipf.whitenoise.android.state

import android.app.Application
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/**
 * Pins the engine-rejection contract of sign-out: an account the engine
 * refuses to deactivate (no key material, no usable external signer) must stay
 * fully signed in — active account, account list, and caches untouched — and
 * the failure must read as a rejection, never as relay-cleanup noise. A
 * transient engine failure keeps the opposite contract: local sign-out still
 * completes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignOutEngineRejectionTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    private val signOutCalls = AtomicInteger(0)
    private val wipeCalls = AtomicInteger(0)
    private val listAccountsCalls = AtomicInteger(0)

    private var signOutFailure: Throwable? = null
    private var wipeFailure: Throwable? = null

    private fun externalSignerAccount() =
        AccountSummaryFfi(
            label = ACCOUNT_REF,
            accountIdHex = ACCOUNT_HEX,
            localSigning = false,
            externalSigning = true,
            signedOut = false,
            running = true,
        )

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            // Suspend failures resume the continuation instead of throwing, so
            // checked FFI exceptions keep their type instead of arriving
            // wrapped in UndeclaredThrowableException.
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }
            when (method.name) {
                "signOut" -> {
                    signOutCalls.incrementAndGet()
                    suspendFailure(checkNotNull(signOutFailure) { "test stubbed no signOut failure" })
                }
                "signOutAndWipe" -> {
                    wipeCalls.incrementAndGet()
                    suspendFailure(checkNotNull(wipeFailure) { "test stubbed no signOutAndWipe failure" })
                }
                "listAccounts" -> {
                    listAccountsCalls.incrementAndGet()
                    listOf(externalSignerAccount())
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "SignOutRejectionMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else ->
                    if (arguments?.lastOrNull() is Continuation<*>) {
                        suspendFailure(UnsupportedOperationException("Unexpected Marmot call: ${method.name}"))
                    } else {
                        throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
                    }
            }
        } as MarmotInterface

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(externalSignerAccount()),
            activeAccountRef = ACCOUNT_REF,
        ).also { it.marmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = marmot) }

    @Test
    fun rejectedSignOutKeepsTheSessionUntouched() =
        runBlocking {
            signOutFailure = MarmotKitException.SecretNotFound("external-signer account gate")
            val appState = appState()
            val phaseBefore = appState.phase

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.AccountRemovalRejected, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(listOf(ACCOUNT_REF), appState.accounts.map { it.label })
            assertEquals(phaseBefore, appState.phase)
            assertEquals(
                "a rejected sign-out must return before any teardown or account re-enumeration",
                0,
                listAccountsCalls.get(),
            )
        }

    @Test
    fun unavailableExternalSignerAlsoReadsAsRejection() =
        runBlocking {
            signOutFailure = MarmotKitException.ExternalSignerUnavailable(ACCOUNT_HEX)
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.AccountRemovalRejected, completion)
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(0, listAccountsCalls.get())
        }

    @Test
    fun transientEngineFailureStillSignsOutLocally() =
        runBlocking {
            signOutFailure = RuntimeException("relay unreachable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.RelayCleanupIncomplete, completion)
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    @Test
    fun rejectedWipeKeepsTheAccount() =
        runBlocking {
            wipeFailure = MarmotKitException.SecretNotFound("external-signer account gate")
            val appState = appState()

            val outcome = appState.signOutAndWipeActiveAccount()

            assertNull(outcome)
            assertEquals(1, wipeCalls.get())
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(listOf(ACCOUNT_REF), appState.accounts.map { it.label })
        }

    private companion object {
        const val ACCOUNT_REF = "external-account"
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
