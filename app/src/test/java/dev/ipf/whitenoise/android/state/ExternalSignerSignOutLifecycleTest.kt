package dev.ipf.whitenoise.android.state

import android.app.Application
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalCleanupReportFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.share.ShareShortcutTarget
import dev.ipf.whitenoise.android.share.buildShareShortcut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Android-side coverage for #2132 after MarmotKit 0.9.15 made external-signer
 * accounts first-class sign-out participants. Android must follow MDK's
 * structured local-cleanup verdict instead of inferring support from signing
 * mode or clearing the active session after an unfinished engine teardown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalSignerSignOutLifecycleTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val signOutCalls = AtomicInteger(0)
    private val wipeCalls = AtomicInteger(0)
    private val listAccountsCalls = AtomicInteger(0)

    private var signOutOutcome =
        SignOutOutcomeFfi(
            keyPackagesDeleted = 1u,
            keyPackageFailures = emptyList(),
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )
    private var signOutFailure: Throwable? = null
    private var listAccountsFailure: Throwable? = null
    private var engineSignedOut = false
    private var wipeOutcome =
        WipeOutcomeFfi(
            groupsLeft = 1u,
            groupLeaveFailures = emptyList(),
            keyPackagesDeleted = 1u,
            keyPackageFailures = emptyList(),
            localCleanup = LocalCleanupReportFfi(completed = true, reason = null),
        )
    private var engineWiped = false

    private fun externalSignerAccount(
        signedOut: Boolean = false,
        running: Boolean = !signedOut,
    ) = AccountSummaryFfi(
        label = ACCOUNT_REF,
        accountIdHex = ACCOUNT_HEX,
        localSigning = false,
        externalSigning = true,
        signedOut = signedOut,
        running = running,
    )

    @Suppress("UNCHECKED_CAST")
    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            fun suspendFailure(failure: Throwable): Any {
                (arguments!!.last() as Continuation<Any?>).resumeWithException(failure)
                return COROUTINE_SUSPENDED
            }

            when (method.name) {
                "signOut" -> {
                    signOutCalls.incrementAndGet()
                    signOutFailure?.let(::suspendFailure)
                        ?: signOutOutcome.also { engineSignedOut = it.localCleanup.completed }
                }
                "signOutAndWipe" -> {
                    wipeCalls.incrementAndGet()
                    wipeOutcome.also { engineWiped = it.localCleanup.completed }
                }
                "listAccounts" -> {
                    listAccountsCalls.incrementAndGet()
                    listAccountsFailure?.let(::suspendFailure)
                    if (engineWiped) emptyList() else listOf(externalSignerAccount(signedOut = engineSignedOut))
                }
                "accountUnreadSummary", "chatList" -> emptyList<Any>()
                "toString" -> "ExternalSignerSignOutMarmotFake"
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

    @Before
    fun setUp() {
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    private fun appState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = listOf(externalSignerAccount()),
            activeAccountRef = ACCOUNT_REF,
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
        }

    @Test
    fun successfulExternalSignerSignOutUsesTheNormalCompletionPath() =
        runBlocking {
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.Complete, completion)
            assertEquals(1, signOutCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    @Test
    fun unfinishedEngineTeardownKeepsTheExternalSignerSessionActive() =
        runBlocking {
            signOutOutcome =
                signOutOutcome.copy(
                    localCleanup =
                        LocalCleanupReportFfi(
                            completed = false,
                            reason = "account worker still active",
                        ),
                )
            val appState = appState()
            val phaseBefore = appState.phase

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.AccountCleanupIncomplete, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(0, listAccountsCalls.get())
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(listOf(ACCOUNT_REF), appState.accounts.map { it.label })
            assertEquals(phaseBefore, appState.phase)
        }

    @Test
    fun transientEngineFailureRetainsTheExistingLocalSignOutFallback() =
        runBlocking {
            signOutFailure = RuntimeException("relay unavailable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.RelayCleanupIncomplete, completion)
            assertEquals(1, signOutCalls.get())
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    @Test
    fun successfulSignOutRefreshFailureStillClearsTheActiveSession() =
        runBlocking {
            listAccountsFailure = RuntimeException("account refresh unavailable")
            val appState = appState()

            val completion = appState.signOutActiveAccount(deleteKeyPackages = true)

            assertEquals(SignOutCompletion.Complete, completion)
            assertEquals(1, signOutCalls.get())
            assertEquals(1, listAccountsCalls.get())
            assertTrue(appState.accounts.single().signedOut)
            assertFalse(appState.accounts.single().running)
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
        }

    @Test
    fun successfulExternalSignerWipeUsesTheNormalRemovalPath() =
        runBlocking {
            val shortcutId = publishConversationShortcut()
            val appState = appState()

            val outcome = appState.signOutAndWipeActiveAccount()

            assertEquals(wipeOutcome, outcome)
            assertEquals(1, wipeCalls.get())
            assertTrue(listAccountsCalls.get() > 0)
            assertTrue(appState.accounts.isEmpty())
            assertNull(appState.activeAccountRef)
            assertTrue(appState.phase is AppPhase.Onboarding)
            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).none { it.id == shortcutId })
        }

    @Test
    fun unfinishedExternalSignerWipeRestoresTheActiveSession() =
        runBlocking {
            val shortcutId = publishConversationShortcut()
            wipeOutcome =
                wipeOutcome.copy(
                    localCleanup =
                        LocalCleanupReportFfi(
                            completed = false,
                            reason = "account worker still active",
                        ),
                )
            val appState = appState()
            val phaseBefore = appState.phase

            val outcome = appState.signOutAndWipeActiveAccount()

            assertEquals(wipeOutcome, outcome)
            assertEquals(1, wipeCalls.get())
            assertEquals(0, listAccountsCalls.get())
            assertEquals(listOf(ACCOUNT_REF), appState.accounts.map { it.label })
            assertEquals(ACCOUNT_REF, appState.activeAccountRef)
            assertEquals(phaseBefore, appState.phase)
            assertTrue(ShortcutManagerCompat.getDynamicShortcuts(context).any { it.id == shortcutId })
        }

    private fun publishConversationShortcut(): String {
        val shortcut =
            checkNotNull(
                buildShareShortcut(
                    context = context,
                    target = ShareShortcutTarget(ACCOUNT_REF, "group-a", "Test conversation"),
                ),
            )
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        return shortcut.id
    }

    private companion object {
        const val ACCOUNT_REF = "external-account"
        const val ACCOUNT_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
