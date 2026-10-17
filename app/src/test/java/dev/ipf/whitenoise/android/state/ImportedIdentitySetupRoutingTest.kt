package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.OnboardingSubscription
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

/** Legacy import completion must preserve MDK's pending setup/recovery admission for both signer types. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportedIdentitySetupRoutingTest {
    /** An nsec receipt cannot replace unfinished setup with an imported-success announcement. */
    @Test
    fun nsecImportKeepsPendingSetup() = assertPendingImport(amber = false, recoveryRequired = false)

    /** Amber's reconciled signer still requires the same native setup admission. */
    @Test
    fun amberImportKeepsPendingSetup() = assertPendingImport(amber = true, recoveryRequired = false)

    /** A native recovery marker remains authoritative even when no setup controller can be mounted. */
    @Test
    fun nsecImportKeepsRequiredRecovery() = assertPendingImport(amber = false, recoveryRequired = true)

    /** External signer import cannot announce readiness before explicit native recovery either. */
    @Test
    fun amberImportKeepsRequiredRecovery() = assertPendingImport(amber = true, recoveryRequired = true)

    /** Exercises the real import/Amber facade and the setup gate before any activation receipt exists. */
    private fun assertPendingImport(
        amber: Boolean,
        recoveryRequired: Boolean,
    ) = runBlocking {
        val fixture = Fixture(amber, recoveryRequired)
        val state = fixture.state
        try {
            if (amber) {
                state.loginWithAmber()
                assertTrue(fixture.calls.contains("registerExternalSigner"))
                assertNull(state.amberSignInStage)
            } else {
                assertEquals(IdentityImportOutcome.Success, state.importIdentity("nsec1" + "q".repeat(58)))
                assertTrue(fixture.calls.contains("login"))
            }
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals("existing", state.activeAccountRef)
            assertEquals(AppPhase.Ready, state.phase)
            assertNull("pending imports must not announce completed activation", state.transientNotice)
            assertNull("the fake import must reach setup admission without an import failure", state.toast)
            assertFalse(fixture.profileRequests.contains(fixture.imported.accountIdHex))
            assertFalse(fixture.calls.contains("notificationSettings"))
            if (recoveryRequired) {
                assertTrue(state.onboardingRecoveryRequired(fixture.imported.label))
                assertNull(state.accountSetup.controller)
            } else {
                assertEquals(fixture.imported.label, state.accountSetup.controller?.account)
                assertFalse(state.accountSetup.eligible(fixture.imported))
                val controller = checkNotNull(state.accountSetup.controller)
                withTimeout(3_000) {
                    while (
                        !controller.state.value.error &&
                        (controller.state.value.snapshot == null || controller.state.value.busy)
                    ) {
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                        delay(1L)
                    }
                }
                assertFalse("setup reconnect errors must fail the fixture", controller.state.value.error)
                val checkpoint = controller.state.value.snapshot
                assertEquals(fixture.imported.label, checkpoint?.accountIdHex)
            }
        } finally {
            runWithMainLooperPumping { state.accountSetup.close() }
        }
        assertEquals(fixture.openedSubscriptions.get(), fixture.closedSubscriptions.get())
        if (!recoveryRequired) assertEquals(1, fixture.openedSubscriptions.get())
    }

    /** Joins Main-owned setup jobs while advancing Robolectric's paused looper, including IO returns. */
    private suspend fun <T> runWithMainLooperPumping(block: suspend () -> T): T =
        coroutineScope {
            val call = async(Dispatchers.Main.immediate) { withTimeout(3_000) { block() } }
            try {
                while (!call.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
                call.await()
            } finally {
                call.cancel()
            }
        }

    /** Supplies native setup eligibility while retaining a previously usable account and Ready shell. */
    private class Fixture(
        amber: Boolean,
        private val recoveryRequired: Boolean,
    ) {
        val calls = ConcurrentLinkedQueue<String>()
        val profileRequests = ConcurrentLinkedQueue<String>()
        val openedSubscriptions = AtomicInteger()
        val closedSubscriptions = AtomicInteger()
        private val existing = AccountSummaryFfi("existing", "aa".repeat(32), true, false, false, true)
        val imported = AccountSummaryFfi("bb".repeat(32), "bb".repeat(32), !amber, amber, false, true)
        private val pending = setupSnapshot(actions = emptyList()).copy(accountIdHex = imported.label)
        private val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                val name = method.name.substringBefore('-')
                calls += name
                when (name) {
                    "accountIdHex" -> imported.accountIdHex
                    "beginExternalSignerOnboarding" -> failSuspendedCall(arguments)
                    "loginExternalSigner" -> imported
                    "registerExternalSigner" -> Unit
                    "listAccounts" -> listOf(existing, imported)
                    "attachmentDownloadPolicy" -> AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                    "beginAttachmentPermissionUpdate" -> 1uL
                    "setAttachmentAutomaticPermission" -> true
                    "accountUnreadSummary", "chatList" -> emptyList<Any>()
                    "onboardingRecoveryRequired" -> arguments?.firstOrNull() == imported.label && recoveryRequired
                    "onboardingSnapshot" -> pending.takeIf { arguments?.firstOrNull() == imported.label }
                    "subscribeOnboarding" -> subscription()
                    "runOnboarding" -> pending
                    "toString" -> "ImportedIdentitySetupMarmotFake"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected Marmot call: $name")
                }
            } as MarmotInterface

        val state: WhiteNoiseAppState

        init {
            val context = ApplicationProvider.getApplicationContext<Context>()
            state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { null },
                    accounts = listOf(existing),
                    activeAccountRef = existing.label,
                    initialMarmotRuntime = AppMarmotRuntime("test", marmot),
                    profileReader = { null },
                    profileRefreshRequest = { profileRequests += it },
                    identityLoginCalls = loginCalls(),
                )
            state.amberPublicKeyRequest = { imported.accountIdHex }
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("setPhase", AppPhase::class.java)
                .apply { isAccessible = true }
                .invoke(state, AppPhase.Ready)
        }

        /** Returns an import receipt only after the legacy identity-only path declines setup ownership. */
        private fun loginCalls() =
            object : IdentityLoginCalls {
                /** Records the actual legacy-login boundary used by nsec import. */
                override suspend fun login(
                    nsec: String,
                    relays: List<String>,
                    keyPackageRelays: List<String>,
                ): AccountSummaryFfi {
                    calls += "login"
                    return imported
                }

                /** Recovery commands are outside these read-only eligibility scenarios. */
                override suspend fun loginRecoveringIncompleteSetup(
                    nsec: String,
                    relays: List<String>,
                    keyPackageRelays: List<String>,
                    acknowledgePossibleKeyPackageOrphan: Boolean,
                ): AccountSummaryFfi = error("unexpected destructive recovery")
            }

        /** Retains the native checkpoint while its stream completes without manufacturing setup success. */
        private fun subscription(): OnboardingSubscription {
            openedSubscriptions.incrementAndGet()
            return object : OnboardingSubscription(NoPointer) {
                /** Provides the same pending native revision mounted by the account setup route. */
                override fun snapshot() = pending

                /** A terminated stream retains its last known pending checkpoint. */
                override suspend fun next(): OnboardingSnapshotFfi? = null

                /** The fake holds no native resources. */
                override fun close() {
                    closedSubscriptions.incrementAndGet()
                }
            }
        }

        /** Delivers the typed legacy-only refusal through the same suspended Amber boundary as production. */
        private fun failSuspendedCall(arguments: Array<out Any?>?): Any {
            @Suppress("UNCHECKED_CAST")
            val continuation = arguments?.last() as Continuation<Any?>
            continuation.resumeWithException(MarmotKitException.OnboardingActionUnavailable())
            return COROUTINE_SUSPENDED
        }
    }
}
